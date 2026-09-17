package com.modelcollab.diagram.service;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.OfflineSyncRequest.PendingOperation;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementa el "Algoritmo de Rebase en Backend" (seccion 11.2 del documento de
 * arquitectura, {@code POST /api/v1/diagrams/{id}/sync-offline}, UC12): recibe la
 * cola de {@link OfflineSyncRequest.PendingOperation} acumulada por un cliente
 * mientras estuvo desconectado y la reproduce, en orden, contra el estado actual
 * del servidor.
 *
 * <p><b>No reimplementa las reglas de aceptacion/descarte por tipo de operacion</b>
 * (MOVE_CLASS idempotente, ADD_* rechaza duplicados, ADD_ATTRIBUTE/ADD_METHOD
 * rechaza si la clase contenedora ya no existe, UPDATE_* rechaza si el target fue
 * eliminado, DELETE_* siempre gana): todo eso ya lo resuelve
 * {@link CanonicalModelMutator} (invocado via {@link DiagramMutationService}), que
 * es la misma capa que aplica las mutaciones en tiempo real por STOMP. El trabajo
 * de esta clase es el bucle de reconciliacion: iterar la cola en el orden en que
 * llego (ese orden ya refleja el {@code clientTimestamp} relativo original, no se
 * reordena aqui), asignarle a cada operacion aplicada su propio numero de secuencia
 * autoritativo via {@link SequenceService} (igual que {@code CollaborationStompController}
 * para que quede intercalada correctamente en {@code diagram_operations} junto con
 * mutaciones concurrentes de otros usuarios), contar aplicadas/descartadas y armar
 * el {@link ValidationReportDTO}.
 *
 * <p><b>Convencion de {@code targetId} dentro del payload:</b> a diferencia del canal
 * STOMP ({@code StompMutationMessage}), donde el {@code targetId} viaja en un campo
 * separado del envelope, {@link PendingOperation#payload()} es un unico string JSON.
 * Por eso esta clase reserva, dentro de ese JSON, una clave opcional
 * {@code "targetId"} (UUID en formato string) con el mismo significado que el
 * {@code targetId} que el cliente le pasaria a {@code CollaborationStompController}
 * para esa misma {@link OperationType} (p.ej. el {@code classId} contenedor para
 * {@code ADD_ATTRIBUTE}, el {@code attributeId} para {@code UPDATE_ATTRIBUTE}/
 * {@code DELETE_ATTRIBUTE}, etc.). Esa clave se extrae y se remueve antes de pasar
 * el resto del mapa como {@code payload} a {@link DiagramMutationService}; para
 * operaciones que no necesitan target (p.ej. {@code ADD_CLASS}, {@code ADD_RELATIONSHIP})
 * simplemente no se incluye.
 */
@Service
public class OfflineReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OfflineReconciliationService.class);

    private static final String TARGET_ID_KEY = "targetId";

    private final DiagramMutationService diagramMutationService;
    private final SequenceService sequenceService;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public OfflineReconciliationService(DiagramMutationService diagramMutationService,
                                         SequenceService sequenceService,
                                         ObjectMapper objectMapper,
                                         SimpMessagingTemplate messagingTemplate) {
        this.diagramMutationService = diagramMutationService;
        this.sequenceService = sequenceService;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Reconcilia la cola offline de un cliente contra el estado actual del diagrama.
     * Procesa {@code request.operations()} en el orden en que vienen (el cliente ya
     * las ordeno por su {@code clientTimestamp} local antes de enviarlas).
     */
    public ValidationReportDTO reconcile(UUID diagramId, UUID userId, OfflineSyncRequest request) {
        int appliedCount = 0;
        List<String> discardedDetails = new ArrayList<>();

        for (PendingOperation pending : request.operations()) {
            OperationType type = parseOperationType(pending.operationType());
            if (type == null) {
                discardedDetails.add(label(pending, null, null) + " descartado: tipo de operacion desconocido '"
                        + pending.operationType() + "'.");
                continue;
            }

            Map<String, Object> mutablePayload = new HashMap<>(deserializePayload(pending.payload()));
            UUID targetId = extractTargetId(mutablePayload);

            long sequenceNum = sequenceService.next(diagramId);
            DiagramMutationService.MutationApplyResult result = diagramMutationService.applyWithReason(
                    diagramId, type, targetId, userId, sequenceNum, mutablePayload);

            if (result.applied()) {
                appliedCount++;
                broadcast(diagramId, type, targetId, userId, sequenceNum, mutablePayload);
            } else {
                discardedDetails.add(label(pending, type, mutablePayload) + " descartado: " + result.reason() + ".");
            }
        }

        return new ValidationReportDTO(appliedCount, discardedDetails.size(), discardedDetails);
    }

    private OperationType parseOperationType(String rawType) {
        try {
            return OperationType.valueOf(rawType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException ex) {
            log.warn("No se pudo deserializar el payload offline: {}", ex.getMessage());
            return Map.of();
        }
    }

    private UUID extractTargetId(Map<String, Object> mutablePayload) {
        Object raw = mutablePayload.remove(TARGET_ID_KEY);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Etiqueta legible usada en {@code discardedDetails}: {@code "<operationType> '<nombre>'"}
     * cuando el payload trae un campo {@code name} (ADD_ATTRIBUTE, ADD_METHOD, RENAME_CLASS,
     * etc.), o {@code "<operationType> (id <clientMutationId>)"} como fallback cuando no hay
     * nombre disponible (p.ej. DELETE_* o un operationType invalido).
     */
    private String label(PendingOperation pending, OperationType type, Map<String, Object> payload) {
        String typeLabel = type != null ? type.name() : pending.operationType();
        Object name = payload == null ? null : payload.get("name");
        if (name != null) {
            return typeLabel + " '" + name + "'";
        }
        return typeLabel + " (clientMutationId " + pending.clientMutationId() + ")";
    }

    /**
     * Difunde la operacion reconciliada a la sala STOMP del diagrama, igual que
     * {@code CollaborationStompController.applyAndBroadcast}, para que los
     * colaboradores conectados vean en vivo los cambios que trajo la reconexion.
     */
    private void broadcast(UUID diagramId, OperationType type, UUID targetId, UUID userId,
                            long sequenceNum, Map<String, Object> payload) {
        long serverTimestamp = System.currentTimeMillis();
        StompBroadcastMessage message =
                new StompBroadcastMessage(sequenceNum, type, targetId, userId, serverTimestamp, payload);
        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, message);
    }
}
