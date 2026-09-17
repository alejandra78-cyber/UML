package com.modelcollab.collaboration.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resuelve conflictos para las operaciones catalogadas como "LWW Libre"
 * (MOVE_CLASS, UPDATE_WAYPOINTS, RESIZE_CLASS): sin lock, gana el timestamp de
 * servidor mas reciente por targetId. Se usa timestamp de SERVIDOR (no del
 * cliente) para evitar problemas de relojes desincronizados entre clientes.
 *
 * <p>Este resolver no aplica el valor a ningun modelo: solo decide si una
 * actualizacion entrante es mas reciente que la ultima aceptada para ese
 * targetId, de modo que el controlador sepa si debe difundirla o descartarla
 * en silencio (la mutacion perdedora simplemente no se retransmite).</p>
 */
@Service
public class LwwConflictResolver {

    private final Map<UUID, Long> lastAcceptedTimestampByTarget = new ConcurrentHashMap<>();

    /**
     * Decide si una nueva actualizacion para {@code targetId} con timestamp de
     * servidor {@code serverTimestamp} debe aplicarse (difundirse), comparandola
     * contra la ultima aceptada. Actualiza el estado interno atomicamente solo si
     * la nueva actualizacion gana.
     *
     * @param targetId        elemento afectado (classId o relId)
     * @param serverTimestamp timestamp epoch-millis autoritativo asignado por el servidor
     * @param newValue        valor entrante (se devuelve tal cual si gana, para conveniencia del llamador)
     * @return resultado indicando si se debe aplicar/difundir la actualizacion
     */
    public <T> LwwResult<T> resolve(UUID targetId, long serverTimestamp, T newValue) {
        boolean[] applied = new boolean[1];
        lastAcceptedTimestampByTarget.compute(targetId, (id, lastTimestamp) -> {
            if (lastTimestamp == null || serverTimestamp >= lastTimestamp) {
                applied[0] = true;
                return serverTimestamp;
            }
            applied[0] = false;
            return lastTimestamp;
        });
        return new LwwResult<>(applied[0], newValue);
    }

    /**
     * Elimina el estado de un target (p.ej. al eliminarse la clase/relacion), evitando
     * fugas de memoria indefinidas en diagramas de vida larga.
     */
    public void forget(UUID targetId) {
        lastAcceptedTimestampByTarget.remove(targetId);
    }

    /**
     * @param applied        true si {@code effectiveValue} gano la resolucion LWW y debe difundirse
     * @param effectiveValue el valor entrante evaluado
     */
    public record LwwResult<T>(boolean applied, T effectiveValue) {
    }
}
