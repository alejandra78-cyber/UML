package com.modelcollab.vision.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Implementacion real de {@link VisionGeminiClient} (el nombre del puerto quedo de
 * cuando el proveedor era Gemini -- no se renombro la interfaz ni {@code VisionOcrService}
 * a proposito, ver migracion Gemini -> OpenAI mas abajo). Llama a la misma API de Chat
 * Completions de OpenAI que {@code OpenAiApiClientImpl} (UC08/UC09), pero con contenido
 * multimodal: un mensaje de usuario con dos partes, {@code type: "text"} (el prompt) y
 * {@code type: "image_url"} con la imagen como data URI en base64 (formato de OpenAI
 * para Vision -- no existe un campo separado para binarios, a diferencia de la API de
 * Gemini que usaba {@code inline_data}).
 *
 * <h2>Migracion Gemini -> OpenAI (motivo)</h2>
 * <p>Mismo motivo que {@code OpenAiApiClientImpl}: el tier gratuito de Gemini tiene un
 * limite de 20 peticiones diarias por proyecto, insuficiente para el volumen de pruebas
 * de este proyecto. Se reemplazo {@code VisionGeminiClientImpl} (eliminada) por esta
 * clase, implementando el mismo puerto {@link VisionGeminiClient} sin tocar
 * {@code VisionOcrService}, que no depende del proveedor de IA concreto.</p>
 *
 * <h2>ADVERTENCIA DE ENTORNO -- sin probar end-to-end</h2>
 * <p>Esta clase no fue ejercitada contra la API real de OpenAI en este entorno de
 * desarrollo al momento de escribirla (la key se completa manualmente despues, en
 * {@code application-local.properties}). La forma del request/response sigue la
 * documentacion publica de la API de Chat Completions de OpenAI con Vision.</p>
 */
@Service
public class OpenAiVisionClientImpl implements VisionGeminiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVisionClientImpl.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAiVisionClientImpl(
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.api.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.model:gpt-5.6-luna}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String generateContent(byte[] imageBytes, String mimeType, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No hay una API key de OpenAI configurada (propiedad 'openai.api.key', "
                            + "resuelta por defecto desde la variable de entorno OPENAI_API_KEY -- ver "
                            + "application.properties). La integracion real con OpenAI no se pudo probar "
                            + "end-to-end en este entorno por esta misma razon: es una limitacion de "
                            + "entorno, no de diseno.");
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + mimeType + ";base64," + base64Image;
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", prompt),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                        ))
                ),
                "response_format", Map.of("type", "json_object")
        );

        String rawResponse = callWithRetryOn429(() -> restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class));

        return extractContent(rawResponse);
    }

    /**
     * Reintento acotado, exclusivo para {@code 429 Too Many Requests} (rate limiting de
     * OpenAI) -- mismo mecanismo que {@code OpenAiApiClientImpl} (UC08/UC09): cualquier
     * otro codigo de error se propaga de inmediato sin reintentar. Como mucho 2
     * reintentos (3 intentos totales), 2s y 5s de espera. Si el ultimo intento tambien
     * da 429, se traduce a {@link ResponseStatusException} 503 con un mensaje claro para
     * el frontend (503, no 429: es la senal que ya entiende {@code VisionModal.tsx}).
     */
    <T> T callWithRetryOn429(Supplier<T> call) {
        long[] retryDelaysMs = {2000L, 5000L};
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException ex) {
                boolean isRateLimited = ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
                if (!isRateLimited || attempt >= retryDelaysMs.length) {
                    if (isRateLimited) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "OpenAI está temporalmente saturado, probá de nuevo en unos minutos", ex);
                    }
                    throw ex;
                }
                log.warn("OpenAI devolvio 429 (intento {}), reintentando en {} ms", attempt + 1,
                        retryDelaysMs[attempt]);
                sleepQuietly(retryDelaysMs[attempt]);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido mientras esperaba para reintentar contra OpenAI", ex);
        }
    }

    /**
     * Extrae {@code choices[0].message.content} del envelope de Chat Completions de
     * OpenAI -- {@code VisionOcrService} espera el texto crudo (JSON segun su propio
     * prompt), no este envelope.
     */
    @SuppressWarnings("unchecked")
    private String extractContent(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("OpenAI no devolvio ninguna respuesta para la imagen enviada");
        }
        Map<String, Object> envelope = objectMapper.readValue(rawResponse, new TypeReference<Map<String, Object>>() {
        });
        List<Object> choices = (List<Object>) envelope.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("OpenAI no devolvio ningun 'choice' para la imagen enviada");
        }
        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        String content = message == null ? null : (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenAI devolvio un choice sin contenido de texto");
        }
        return content;
    }
}
