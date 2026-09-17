package com.modelcollab.generator.service.nlu;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Una intención de voz/texto derivada de una clase del modelo canónico (sección
 * 12.3 del plan arquitectónico). Ver {@link com.modelcollab.generator.service.NluIntentGeneratorService}
 * para el algoritmo de derivación completo.
 *
 * @param name     identificador de la intención en MAYUSCULAS_CON_GUION_BAJO,
 *                 p.ej. {@code "CREAR_CITA"}
 * @param entity   nombre de la clase de origen (PascalCase), p.ej. {@code "Cita"}
 * @param action   una de {@code "CREATE"}, {@code "LIST"}, {@code "SEARCH"}, {@code "DELETE"}
 * @param triggers frases disparadoras en español (sinónimos razonables, no exhaustivos)
 * @param slots    parámetros de la intención; se omite del JSON si está vacío (p.ej.
 *                 las intenciones {@code LISTAR_*} no llevan slots, igual que el
 *                 ejemplo de la sección 12.3 del plan)
 */
public record IntentSpec(
        String name,
        String entity,
        String action,
        List<String> triggers,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SlotSpec> slots
) {
}
