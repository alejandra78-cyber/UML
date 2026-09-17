package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.metamodel.model.CanonicalModel;

/**
 * Puerto hacia el sistema externo Gemini (actor secundario "Gemini" del documento de
 * arquitectura, seccion 2.1) para interpretar un comando de voz/texto (UC08/UC09) contra
 * el {@code CanonicalModel} actual de un diagrama.
 *
 * <p>Se declara como interfaz (con {@link GeminiApiClientImpl} como unica implementacion
 * real, que hace la llamada HTTP de verdad) precisamente para que
 * {@code VoiceCommandParserService} sea testeable con un stub/fake sin red -- ver
 * advertencia tecnica #4 de la tarea: no hay API key de Gemini configurada en este
 * entorno, asi que la implementacion real nunca se ejercito end-to-end en estos tests.</p>
 */
public interface GeminiApiClient {

    /**
     * Interpreta un comando en lenguaje natural contra el modelo actual y devuelve la
     * lista de operaciones atomicas que Gemini propone aplicar.
     *
     * @param currentModel estado canonico actual del diagrama (contexto para Gemini:
     *                     nombres e ids de clases existentes, para que pueda referenciarlas)
     * @param command      orden en lenguaje natural (ya pasada el guardrail GENERATE_DOMAIN)
     * @return operaciones interpretadas, en el orden en que deben aplicarse
     */
    GeminiInterpretationResult interpretCommand(CanonicalModel currentModel, String command);
}
