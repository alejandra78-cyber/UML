package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.metamodel.model.CanonicalModel;

/**
 * Puerto hacia el proveedor de IA externo usado para interpretar un comando de
 * voz/texto (UC08/UC09) contra el {@code CanonicalModel} actual de un diagrama.
 *
 * <p><b>Nombre historico:</b> esta interfaz (y el DTO {@link GeminiInterpretationResult})
 * se llama "Gemini" porque ese fue el proveedor original; el proveedor real hoy es
 * OpenAI ({@code OpenAiApiClientImpl}, ver su javadoc para el motivo de la migracion).
 * No se renombro la interfaz ni sus consumidores ({@code VoiceCommandParserService})
 * al migrar de proveedor a proposito: ninguno de los dos depende de cual sea el
 * proveedor concreto, solo de este contrato.</p>
 *
 * <p>Se declara como interfaz (con {@code OpenAiApiClientImpl} como unica implementacion
 * real, que hace la llamada HTTP de verdad) precisamente para que
 * {@code VoiceCommandParserService} sea testeable con un stub/fake sin red.</p>
 */
public interface GeminiApiClient {

    /**
     * Interpreta un comando en lenguaje natural contra el modelo actual y devuelve la
     * lista de operaciones atomicas que el proveedor de IA propone aplicar.
     *
     * @param currentModel estado canonico actual del diagrama (contexto para la IA:
     *                     nombres e ids de clases existentes, para que pueda referenciarlas)
     * @param command      orden en lenguaje natural (ya pasada el guardrail GENERATE_DOMAIN)
     * @return operaciones interpretadas, en el orden en que deben aplicarse
     */
    GeminiInterpretationResult interpretCommand(CanonicalModel currentModel, String command);
}
