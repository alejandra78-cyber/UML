package com.example.demo.collaboration.dto;

import java.util.UUID;

/**
 * Mensaje entrante del cliente a {@code /app/diagram/{id}/lock}.
 *
 * @param action   ACQUIRE, RELEASE o RENEW (heartbeat cada 2000 ms para refrescar el TTL)
 * @param targetId elemento sobre el que se solicita/libera/renueva el lock
 * @param userId   usuario solicitante
 */
public record LockActionRequest(Action action, UUID targetId, UUID userId) {
    public enum Action {
        ACQUIRE,
        RELEASE,
        RENEW
    }
}
