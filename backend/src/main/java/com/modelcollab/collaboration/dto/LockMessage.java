package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Familia sellada de mensajes de salida del motor de locks (seccion 6, mecanismo
 * de exclusion mutua por soft-locks con TTL). {@link LockAcquired} y
 * {@link LockReleased} se difunden a {@code /topic/diagrams/{id}}; {@link LockDenied}
 * se envia SOLO al solicitante via {@code /user/queue/locks} (fallo rapido, sin cola
 * de espera, para evitar deadlocks/inanicion).
 */
public sealed interface LockMessage permits LockMessage.LockAcquired, LockMessage.LockReleased, LockMessage.LockDenied {

    UUID targetId();

    /**
     * Lock concedido con exito.
     *
     * @param ttl milisegundos de vigencia (5000 ms por defecto) antes de expirar si no se renueva
     */
    record LockAcquired(UUID targetId, UUID userId, String userName, String color, long ttl) implements LockMessage {
    }

    /**
     * El lock fue liberado (explicitamente, por expiracion de TTL o por desconexion del titular).
     */
    record LockReleased(UUID targetId) implements LockMessage {
    }

    /**
     * El recurso ya esta tomado por otro usuario; respuesta dirigida solo al solicitante,
     * nunca se hace esperar a un cliente (no hay cola de espera).
     */
    record LockDenied(UUID targetId, UUID heldBy, long expiresInMs) implements LockMessage {
    }
}
