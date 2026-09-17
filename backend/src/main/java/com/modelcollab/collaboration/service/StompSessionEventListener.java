package com.modelcollab.collaboration.service;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Optional;

/**
 * Escucha la desconexion de sesiones STOMP para limpiar el estado efimero de
 * colaboracion asociado: retira la presencia de {@link RoomManager} y libera
 * cualquier soft-lock que el usuario desconectado tuviera abierto en
 * {@link LockManagerService} (seccion 6, punto 3: "auto-release ... o el
 * cliente se desconecta").
 */
@Component
public class StompSessionEventListener {

    private final RoomManager roomManager;
    private final LockManagerService lockManagerService;

    public StompSessionEventListener(RoomManager roomManager, LockManagerService lockManagerService) {
        this.roomManager = roomManager;
        this.lockManagerService = lockManagerService;
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        Optional<RoomManager.SessionBinding> binding = roomManager.leaveBySession(sessionId);
        binding.ifPresent(b -> lockManagerService.releaseAllForUser(b.userId()));
    }
}
