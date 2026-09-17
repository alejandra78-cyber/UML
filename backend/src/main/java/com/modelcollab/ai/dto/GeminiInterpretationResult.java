package com.modelcollab.ai.dto;

import java.util.List;

/**
 * Resultado de pedirle a Gemini que interprete un comando de voz/texto contra el
 * {@code CanonicalModel} actual de un diagrama. Es el contrato entre
 * {@code GeminiApiClient} y {@code VoiceCommandParserService}: deliberadamente un
 * record simple (en vez de que {@code interpretCommand} devuelva {@code List<AiOperation>}
 * a secas) para poder agregar campos a futuro (p.ej. una explicacion en lenguaje
 * natural de lo que Gemini entendio) sin romper la firma del metodo.
 *
 * @param operations operaciones interpretadas, en el orden en que deben aplicarse
 */
public record GeminiInterpretationResult(List<AiOperation> operations) {
    public GeminiInterpretationResult {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public static GeminiInterpretationResult empty() {
        return new GeminiInterpretationResult(List.of());
    }
}
