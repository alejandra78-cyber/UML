package com.modelcollab.generator.service.nlu;

import java.util.List;

/**
 * Raíz del documento {@code intents.json} (sección 12.3 del plan arquitectónico):
 * la configuración de NLU local que consume el motor de reglas/slots offline de
 * la app móvil generada.
 */
public record IntentsDocument(List<IntentSpec> intents) {
    public IntentsDocument {
        intents = intents == null ? List.of() : List.copyOf(intents);
    }
}
