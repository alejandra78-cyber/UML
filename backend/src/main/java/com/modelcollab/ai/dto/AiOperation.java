package com.modelcollab.ai.dto;

import com.modelcollab.collaboration.dto.OperationType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Una operacion atomica del catalogo {@link OperationType}, tal como la interpreta
 * {@code GeminiApiClient} a partir de un comando de voz/texto. Misma convencion de
 * payload que {@code CanonicalModelMutator} (ver su Javadoc): para {@code ADD_*} el
 * payload es el objeto nuevo completo; para {@code UPDATE_*}/{@code RENAME_*} es un
 * merge parcial; para {@code DELETE_*} el payload puede venir vacio ({@code targetId}
 * alcanza).
 *
 * @param type     operacion del catalogo cerrado
 * @param targetId elemento afectado (null si la operacion se aplica sobre la raiz del
 *                 diagrama, p.ej. {@code ADD_CLASS}/{@code ADD_RELATIONSHIP})
 * @param payload  datos especificos de la operacion (nunca null: usar {@code Map.of()})
 */
public record AiOperation(OperationType type, UUID targetId, Map<String, Object> payload) {
    public AiOperation {
        // OJO: Map.copyOf (y Map.of) rechazan valores null con NullPointerException.
        // Gemini puede devolver explicitamente "campo": null para un campo opcional que
        // omitio (bug real encontrado con Gemini real: ADD_CLASS con "width": null hacia
        // fallar la construccion de este record ANTES de que withDefaults() de
        // CanonicalModelMutator llegara a ejecutarse). Mismo patron ya resuelto en
        // StompMutationMessage/StompBroadcastMessage: un LinkedHashMap envuelto en
        // Collections.unmodifiableMap tolera esos valores, Map.copyOf no.
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
