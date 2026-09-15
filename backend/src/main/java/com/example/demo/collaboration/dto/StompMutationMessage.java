package com.example.demo.collaboration.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope generico enviado por el cliente a {@code /app/diagram/{id}/mutate}.
 *
 * <p>El payload se modela como {@code Map<String,Object>} (JSON arbitrario) en lugar
 * de un tipo especifico por cada {@link OperationType}: este modulo actua como capa
 * de transporte/relay (valida rol, lock y orden LWW) y reenvia el payload tal cual al
 * resto de la sala; la aplicacion real de la mutacion sobre el {@code CanonicalModel}
 * persistido (columna JSONB de {@code diagram.model.Diagram}) es responsabilidad de
 * otro modulo. Definir un DTO fuertemente tipado por operacion aqui hubiera acoplado
 * este modulo a un modelo de persistencia que todavia no existe.
 *
 * @param operationType     tipo de operacion atomica (ver {@link OperationType})
 * @param targetId          id del elemento afectado (classId/attrId/relId/methodId/packageId),
 *                          null para operaciones cuyo destino es la raiz del diagrama
 * @param userId            id del usuario emisor
 * @param clientTimestamp   timestamp epoch-millis generado por el cliente (para LWW)
 * @param payload           datos especificos de la operacion (JSON libre)
 */
public record StompMutationMessage(
        OperationType operationType,
        UUID targetId,
        UUID userId,
        long clientTimestamp,
        Map<String, Object> payload
) {
    public StompMutationMessage {
        // OJO: Map.copyOf (y Map.of) rechazan valores null con NullPointerException.
        // Campos legítimamente nullables del esquema canónico viajan dentro de este
        // payload (p.ej. Attribute.defaultValue), así que un LinkedHashMap envuelto en
        // Collections.unmodifiableMap es el único que tolera esos valores: con
        // Map.copyOf, cualquier ADD_ATTRIBUTE/UPDATE_ATTRIBUTE con defaultValue=null (el
        // caso mas comun, ya que la mayoria de los atributos no tienen default) hacia
        // fallar la deserializacion completa del mensaje con una excepcion invisible
        // para el cliente (el SEND se perdia sin ningun error ni broadcast).
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
