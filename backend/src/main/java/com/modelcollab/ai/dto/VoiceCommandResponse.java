package com.modelcollab.ai.dto;

import java.util.List;

/**
 * Respuesta de {@code POST /api/v1/diagrams/{diagramId}/ai-command} (UC08/UC09).
 *
 * <p>Dos formas posibles segun {@link #guardrailRejected()}:</p>
 * <ul>
 *   <li>{@code true}: el guardrail {@code GENERATE_DOMAIN} (RF-02.6) rechazo la orden
 *   antes de invocar a Gemini. {@link #guardrailMessage()} trae el mensaje literal del
 *   plan arquitectonico; el resto de los campos quedan en su valor neutro (0/false/lista
 *   vacia).</li>
 *   <li>{@code false}: la orden paso el guardrail y se interpreto (o se intento
 *   interpretar). {@link #appliedCount()}/{@link #rejectedCount()} cuentan cuantas de
 *   las operaciones interpretadas se aplicaron efectivamente via
 *   {@code DiagramMutationService#applyWithReason}, {@link #truncated()} indica si
 *   Gemini devolvio mas de 5 operaciones y se descartaron las excedentes (tope duro de
 *   RF-02.6), y {@link #rejectedReasons()} trae el motivo de cada operacion rechazada
 *   (target inexistente, payload invalido, etc.).</li>
 * </ul>
 *
 * @param guardrailRejected true si el guardrail GENERATE_DOMAIN bloqueo la orden
 * @param guardrailMessage  mensaje literal de rechazo (null si no se rechazo)
 * @param appliedCount      cantidad de operaciones aplicadas con exito
 * @param rejectedCount     cantidad de operaciones interpretadas pero no aplicadas
 * @param truncated         true si Gemini devolvio mas de 5 operaciones y se recortaron
 * @param rejectedReasons   motivo de cada operacion no aplicada (mismo orden que se procesaron)
 */
public record VoiceCommandResponse(
        boolean guardrailRejected,
        String guardrailMessage,
        int appliedCount,
        int rejectedCount,
        boolean truncated,
        List<String> rejectedReasons
) {
    public VoiceCommandResponse {
        rejectedReasons = rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons);
    }

    public static VoiceCommandResponse guardrailRejected(String message) {
        return new VoiceCommandResponse(true, message, 0, 0, false, List.of());
    }

    public static VoiceCommandResponse processed(int appliedCount, int rejectedCount, boolean truncated,
                                                  List<String> rejectedReasons) {
        return new VoiceCommandResponse(false, null, appliedCount, rejectedCount, truncated, rejectedReasons);
    }
}
