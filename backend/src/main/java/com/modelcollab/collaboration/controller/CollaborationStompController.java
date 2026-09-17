package com.modelcollab.collaboration.controller;

import com.modelcollab.collaboration.dto.LockActionRequest;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.LockManagerService;
import com.modelcollab.collaboration.service.LwwConflictResolver;
import com.modelcollab.collaboration.service.RoomManager;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.service.DiagramMutationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Punto de entrada STOMP para la colaboracion en tiempo real de un diagrama.
 * Mapea:
 * <ul>
 *   <li>{@code /app/diagram/{id}/lock} - ACQUIRE/RELEASE/RENEW de soft-locks</li>
 *   <li>{@code /app/diagram/{id}/mutate} - catalogo de operaciones atomicas (ver {@link OperationType})</li>
 *   <li>{@code /app/diagram/{id}/presence/join} - alta de presencia en la sala</li>
 *   <li>{@code /app/diagram/{id}/cursor} - USER_CURSOR (canal volatil)</li>
 * </ul>
 *
 * <p>Este controlador valida lock/orden LWW, asigna numero de secuencia y
 * delega en {@link DiagramMutationService} la aplicacion efectiva de la
 * mutacion sobre el {@code CanonicalModel} persistido (JSONB en
 * {@code diagram.model.Diagram}) y su escritura en {@code diagram_operations}.
 * Solo si esa persistencia tiene exito se difunde el broadcast a la sala.</p>
 *
 * <p><b>Identidad del emisor:</b> {@code handleLock}, {@code handleMutate},
 * {@code handleJoin} y {@code handleCursor} obtienen el {@code userId} del
 * {@link Principal} ya autenticado por JWT en la sesion STOMP (ver
 * {@code StompChannelInterceptor}), nunca del campo {@code userId} que el
 * cliente declara en el cuerpo del mensaje. Los campos {@link LockActionRequest#userId()},
 * {@link StompMutationMessage#userId()}, {@link PresenceJoinRequest#userId()} y
 * {@link CursorUpdateRequest#userId()} se conservan en sus respectivos records
 * solo por compatibilidad de payload; el controlador nunca los lee.</p>
 */
@Controller
public class CollaborationStompController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationStompController.class);

    private final LockManagerService lockManagerService;
    private final LwwConflictResolver lwwConflictResolver;
    private final SequenceService sequenceService;
    private final RoomManager roomManager;
    private final DiagramMutationService diagramMutationService;
    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationStompController(LockManagerService lockManagerService,
                                         LwwConflictResolver lwwConflictResolver,
                                         SequenceService sequenceService,
                                         RoomManager roomManager,
                                         DiagramMutationService diagramMutationService,
                                         SimpMessagingTemplate messagingTemplate) {
        this.lockManagerService = lockManagerService;
        this.lwwConflictResolver = lwwConflictResolver;
        this.sequenceService = sequenceService;
        this.roomManager = roomManager;
        this.diagramMutationService = diagramMutationService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/diagram/{id}/lock")
    public void handleLock(@DestinationVariable String id, LockActionRequest request, Principal principal) {
        UUID diagramId = UUID.fromString(id);
        UUID userId = authenticatedUserId(principal);
        switch (request.action()) {
            case ACQUIRE -> {
                RoomManager.RoomMember member = roomManager.findMember(diagramId, userId)
                        .orElse(new RoomManager.RoomMember(userId, "Usuario", "#999999"));
                lockManagerService.acquire(diagramId, request.targetId(), userId, member.userName(), member.color());
            }
            case RELEASE -> lockManagerService.release(request.targetId(), userId);
            case RENEW -> lockManagerService.renew(request.targetId(), userId);
        }
    }

    @MessageMapping("/diagram/{id}/mutate")
    public void handleMutate(@DestinationVariable String id, StompMutationMessage message, Principal principal) {
        UUID diagramId = UUID.fromString(id);
        OperationType type = message.operationType();
        UUID userId = authenticatedUserId(principal);

        if (type == OperationType.ACQUIRE_LOCK || type == OperationType.RELEASE_LOCK) {
            log.warn("Operacion {} recibida en canal /mutate; debe enviarse por /diagram/{}/lock", type, id);
            return;
        }

        long serverTimestamp = System.currentTimeMillis();

        switch (type.mutualExclusionMode()) {
            case LOCK_REQUIRED -> {
                if (!requireOwnedLock(message.targetId(), userId)) {
                    return;
                }
                applyAndBroadcast(diagramId, type, message.targetId(), userId, serverTimestamp, message.payload());
            }
            case DIAGRAM_LOCK -> {
                // BULK_MERGE: exige un lock sobre la raiz del diagrama (targetId = diagramId)
                // adquirido previamente via /diagram/{id}/lock, para que la insercion del
                // subgrafo sea una transaccion unica sin interferencias concurrentes.
                if (!requireOwnedLock(diagramId, userId)) {
                    return;
                }
                applyAndBroadcast(diagramId, type, null, userId, serverTimestamp, message.payload());
            }
            case LWW_FREE -> {
                LwwConflictResolver.LwwResult<Map<String, Object>> result =
                        lwwConflictResolver.resolve(message.targetId(), serverTimestamp, message.payload());
                if (result.applied()) {
                    applyAndBroadcast(diagramId, type, message.targetId(), userId, serverTimestamp, message.payload());
                }
                // Si no se aplico, la actualizacion llego desordenada respecto de una mas
                // reciente ya aceptada: se descarta en silencio (no se informa error al
                // emisor, ya que en 60 FPS esto es esperable y no es un fallo del usuario).
            }
            case NONE -> applyAndBroadcast(diagramId, type, message.targetId(), userId, serverTimestamp, message.payload());
            case LOCK_ENGINE -> log.warn("OperationType {} no deberia llegar a /mutate", type);
        }
    }

    @MessageMapping("/diagram/{id}/presence/join")
    public void handleJoin(@DestinationVariable String id, PresenceJoinRequest request,
                            SimpMessageHeaderAccessor headerAccessor, Principal principal) {
        UUID diagramId = UUID.fromString(id);
        String sessionId = headerAccessor.getSessionId();
        if (sessionId == null) {
            log.warn("Solicitud de presence/join sin sessionId STOMP; se ignora");
            return;
        }
        UUID userId = authenticatedUserId(principal);
        roomManager.join(sessionId, diagramId, userId, request.userName(), request.color());
    }

    @MessageMapping("/diagram/{id}/cursor")
    public void handleCursor(@DestinationVariable String id, CursorUpdateRequest request, Principal principal) {
        UUID diagramId = UUID.fromString(id);
        UUID userId = authenticatedUserId(principal);
        roomManager.broadcastCursor(diagramId, userId, request.userName(), request.color(),
                request.x(), request.y(), request.selectedId());
    }

    /**
     * Extrae el userId autenticado del {@link Principal} vinculado a la sesion
     * STOMP en CONNECT (ver {@code StompChannelInterceptor#authenticateConnect}).
     * Nunca se usa el {@code userId} declarado por el cliente en el cuerpo del
     * mensaje para estos tres canales: el Principal es la unica fuente de verdad.
     */
    private UUID authenticatedUserId(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException(
                    "No hay Principal autenticado en la sesion STOMP (CONNECT deberia haberlo rechazado antes)");
        }
        return UUID.fromString(principal.getName());
    }

    private boolean requireOwnedLock(UUID targetId, UUID userId) {
        if (targetId == null || !lockManagerService.isHeldBy(targetId, userId)) {
            log.debug("Mutacion rechazada: el usuario {} no posee el lock activo de {}", userId, targetId);
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/errors",
                    "No posee el lock activo requerido para modificar " + targetId);
            return false;
        }
        return true;
    }

    /**
     * Asigna numero de secuencia, aplica la mutacion sobre el {@code CanonicalModel}
     * persistido (via {@link DiagramMutationService}) y, solo si tuvo exito, difunde
     * el broadcast a la sala. Si la persistencia rechaza la operacion (diagrama o
     * target inexistente, payload invalido), se informa solo al emisor y no se
     * difunde nada.
     */
    private void applyAndBroadcast(UUID diagramId, OperationType type, UUID targetId, UUID userId,
                                    long serverTimestamp, Map<String, Object> payload) {
        long sequenceNum = sequenceService.next(diagramId);
        boolean applied = diagramMutationService.apply(diagramId, type, targetId, userId, sequenceNum, payload);
        if (!applied) {
            log.debug("Mutacion {} rechazada por DiagramMutationService en diagrama {}", type, diagramId);
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/errors",
                    "La operacion " + type + " no pudo aplicarse (target inexistente o payload invalido)");
            return;
        }

        if (type == OperationType.DELETE_CLASS || type == OperationType.DELETE_RELATIONSHIP) {
            // Evita fugas de memoria indefinidas en el estado LWW del elemento eliminado.
            lwwConflictResolver.forget(targetId);
        }

        StompBroadcastMessage broadcastMessage =
                new StompBroadcastMessage(sequenceNum, type, targetId, userId, serverTimestamp, payload);
        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, broadcastMessage);
    }

    /** Payload minimo que envia el cliente al unirse a la sala de un diagrama. */
    public record PresenceJoinRequest(UUID userId, String userName, String color) {
    }

    /** Payload de actualizacion de cursor/seleccion (operacion USER_CURSOR). */
    public record CursorUpdateRequest(UUID userId, String userName, String color, double x, double y, UUID selectedId) {
    }
}
