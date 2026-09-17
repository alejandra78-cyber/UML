package com.modelcollab.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de {@code POST /api/v1/diagrams/{diagramId}/ai-command} (UC08/UC09, seccion 14
 * del documento de arquitectura). El campo es deliberadamente un unico {@code command}
 * de texto: la voz (UC08) y el texto (UC09) comparten el mismo motor porque, para cuando
 * la peticion llega al backend, ambos canales ya convirtieron la orden a texto plano en
 * el frontend (Web Speech API para voz, {@code CommandPromptInput} para texto) -- el
 * backend nunca recibe audio.
 *
 * @param command orden en lenguaje natural, ya transcripta si vino por voz
 */
public record VoiceCommandRequest(
        @NotBlank(message = "command no puede estar vacio") String command
) {
}
