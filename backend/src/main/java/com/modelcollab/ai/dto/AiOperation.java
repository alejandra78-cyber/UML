package com.modelcollab.ai.dto;

import com.modelcollab.collaboration.dto.OperationType;

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
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
