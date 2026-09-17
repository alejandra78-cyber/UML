package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.PresenceMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona que usuarios estan presentes en la sala STOMP de cada diagrama
 * ({@code /topic/diagrams/{id}}): altas/bajas de presencia y actualizaciones de
 * cursor. Estado puramente en memoria, efimero, no persistido.
 *
 * <p>Tambien mantiene el mapeo sessionId de WebSocket -> (diagramId, userId) para
 * que, ante una desconexion (evento {@code SessionDisconnectEvent}), se pueda
 * saber a que sala y usuario pertenecia la sesion y limpiar tanto la presencia
 * como los soft-locks que tuviera abiertos (ver {@link StompSessionEventListener}).</p>
 */
@Service
public class RoomManager {

    private final Map<UUID, Map<UUID, RoomMember>> membersByDiagram = new ConcurrentHashMap<>();
    private final Map<String, SessionBinding> bindingsBySessionId = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public RoomManager(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Registra a un usuario como presente en la sala del diagrama y difunde
     * {@code PresenceMessage.Type.JOINED}.
     */
    public void join(String sessionId, UUID diagramId, UUID userId, String userName, String color) {
        membersByDiagram
                .computeIfAbsent(diagramId, id -> new ConcurrentHashMap<>())
                .put(userId, new RoomMember(userId, userName, color));
        bindingsBySessionId.put(sessionId, new SessionBinding(diagramId, userId));

        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId,
                new PresenceMessage(PresenceMessage.Type.JOINED, diagramId, userId, userName, color, null, null, null));
    }

    /**
     * Retira la presencia asociada a una sesion STOMP (por LEAVE explicito o por
     * desconexion) y difunde {@code PresenceMessage.Type.LEFT}. Devuelve el binding
     * removido para que el llamador (p.ej. el listener de desconexion) pueda tambien
     * liberar los locks que ese usuario tuviera abiertos.
     */
    public Optional<SessionBinding> leaveBySession(String sessionId) {
        SessionBinding binding = bindingsBySessionId.remove(sessionId);
        if (binding == null) {
            return Optional.empty();
        }
        Map<UUID, RoomMember> members = membersByDiagram.get(binding.diagramId());
        RoomMember removed = members == null ? null : members.remove(binding.userId());
        if (members != null && members.isEmpty()) {
            membersByDiagram.remove(binding.diagramId());
        }
        String userName = removed == null ? null : removed.userName();
        String color = removed == null ? null : removed.color();
        messagingTemplate.convertAndSend("/topic/diagrams/" + binding.diagramId(),
                new PresenceMessage(PresenceMessage.Type.LEFT, binding.diagramId(), binding.userId(), userName, color,
                        null, null, null));
        return Optional.of(binding);
    }

    /**
     * Difunde una actualizacion de cursor/seleccion (operacion USER_CURSOR: canal
     * volatil, no requiere lock ni persiste estado mas alla del ultimo valor implicito
     * en el cliente).
     */
    public void broadcastCursor(UUID diagramId, UUID userId, String userName, String color,
                                 double cursorX, double cursorY, UUID selectedId) {
        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId,
                new PresenceMessage(PresenceMessage.Type.CURSOR_UPDATE, diagramId, userId, userName, color,
                        cursorX, cursorY, selectedId));
    }

    public Collection<RoomMember> membersOf(UUID diagramId) {
        Map<UUID, RoomMember> members = membersByDiagram.get(diagramId);
        return members == null ? java.util.List.of() : java.util.List.copyOf(members.values());
    }

    /**
     * Busca los datos de presencia (nombre, color) de un usuario ya unido a la sala
     * de un diagrama. Usado por el controlador para completar broadcasts de lock
     * (LOCK_ACQUIRED necesita userName/color) sin que el cliente deba reenviarlos.
     */
    public Optional<RoomMember> findMember(UUID diagramId, UUID userId) {
        Map<UUID, RoomMember> members = membersByDiagram.get(diagramId);
        return members == null ? Optional.empty() : Optional.ofNullable(members.get(userId));
    }

    public Optional<SessionBinding> bindingFor(String sessionId) {
        return Optional.ofNullable(bindingsBySessionId.get(sessionId));
    }

    public record RoomMember(UUID userId, String userName, String color) {
    }

    public record SessionBinding(UUID diagramId, UUID userId) {
    }
}
