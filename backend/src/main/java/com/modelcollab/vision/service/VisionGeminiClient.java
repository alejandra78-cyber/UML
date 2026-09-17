package com.modelcollab.vision.service;

/**
 * Puerto de acceso a un modelo multimodal (imagen + texto) que analiza una
 * foto de pizarra y devuelve texto (esperado: JSON segun el prompt de
 * {@code VisionOcrService}). Existe como interfaz inyectable precisamente para
 * que {@code VisionOcrService} se pueda testear con un fake/stub sin red (ver
 * {@code VisionOcrServiceTest}), dado que no hay ninguna API key de Gemini
 * configurada en este entorno de desarrollo.
 *
 * <p><b>Nombrado deliberado ("Vision" + "Gemini", no solo "GeminiClient" o
 * "GeminiApiClient"):</b> otro agente puede estar trabajando en paralelo esta
 * misma oleada en {@code com.modelcollab.ai} (UC08/UC09, comandos de voz/texto)
 * y podria construir su propio cliente Gemini para texto plano. Si para cuando
 * este paquete se integre ya existe {@code com.modelcollab.ai.service.GeminiApiClient},
 * lo correcto es reutilizarlo (la API multimodal de Gemini es la misma, solo
 * cambia el contenido del request) en vez de esta interfaz -- pero al momento
 * de escribir este paquete {@code com.modelcollab.ai} todavia no existe (se
 * verifico con grep sobre todo {@code src/main/java/com/modelcollab} antes de
 * crear esta clase), asi que se crea esta version propia y visiblemente
 * momentanea/con ambito acotado a vision, para minimizar choque de nombres si
 * el otro agente termina despues y crea el suyo.</p>
 */
public interface VisionGeminiClient {

    /**
     * @param imageBytes bytes crudos de la imagen (JPEG o PNG)
     * @param mimeType   {@code image/jpeg} o {@code image/png}
     * @param prompt     instrucciones de texto (incluye el esquema de salida esperado
     *                   y el contexto del {@code CanonicalModel} actual)
     * @return la respuesta de texto del modelo (se espera JSON; el parseo lo hace
     *         quien llama, no esta interfaz)
     */
    String generateContent(byte[] imageBytes, String mimeType, String prompt);
}
