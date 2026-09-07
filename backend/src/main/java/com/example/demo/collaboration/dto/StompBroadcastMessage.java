package com.example.demo.collaboration.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Envelope difundido por el servidor a {@code /topic/diagrams/{id}} tras aceptar
 * una {@link StompMutationMessage}. Incluye el numero de secuencia monotonico
 * (ver {@code SequenceService}) para que los clientes puedan ordenar operaciones
 * concurrentes y detectar huecos, y el timestamp autoritativo de servidor usado
 * por el {@code LwwConflictResolver}.
 *
 * @param sequenceNum      numero de secuencia monotonico por diagrama
 * @param operationType    tipo de operacion atomica
 * @param targetId         id del elemento afectado (o null si aplica a la raiz)
 * @param userId           id del usuario que origino la mutacion
 * @param serverTimestamp  timestamp epoch-millis asignado por el servidor al difundir
 * @param payload          datos especificos de la operacion (reenviados desde el emisor)
 */
public record StompBroadcastMessage(
        long sequenceNum,
        OperationType operationType,
        UUID targetId,
        UUID userId,
        long serverTimestamp,
        Map<String, Object> payload
) {
    public StompBroadcastMessage {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
