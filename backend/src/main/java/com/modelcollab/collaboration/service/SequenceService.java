package com.modelcollab.collaboration.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Genera numeros de secuencia monotonicos crecientes por diagrama, usados para
 * ordenar operaciones en {@code StompBroadcastMessage} (columna {@code sequence_num}
 * de la futura tabla {@code diagram_operations}, gestionada por otro modulo).
 *
 * <p>Estado puramente en memoria: si el proceso se reinicia, la secuencia arranca
 * de nuevo desde 1 para cada diagrama. Esto es aceptable porque el proposito de la
 * secuencia es solo ordenar mensajes dentro de una misma sesion de colaboracion en
 * tiempo real, no servir de identificador persistente.</p>
 */
@Service
public class SequenceService {

    private final Map<UUID, AtomicLong> countersByDiagram = new ConcurrentHashMap<>();

    /**
     * Devuelve el siguiente numero de secuencia para el diagrama dado, comenzando en 1.
     */
    public long next(UUID diagramId) {
        return countersByDiagram
                .computeIfAbsent(diagramId, id -> new AtomicLong(0))
                .incrementAndGet();
    }

    /**
     * Devuelve el ultimo numero de secuencia emitido para el diagrama (0 si ninguno aun).
     */
    public long current(UUID diagramId) {
        AtomicLong counter = countersByDiagram.get(diagramId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Elimina el contador de un diagrama (p.ej. cuando la sala queda vacia), liberando memoria.
     */
    public void reset(UUID diagramId) {
        countersByDiagram.remove(diagramId);
    }
}
