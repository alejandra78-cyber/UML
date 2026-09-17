package com.modelcollab.vision.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Implementacion real (HTTP) de {@link VisionGeminiClient} contra la API REST
 * publica de Gemini 2.0 Flash multimodal ({@code generateContent}, forma real
 * de la API a la fecha de escritura: {@code contents[].parts[]} con una parte
 * de texto y una parte {@code inline_data} en base64 para la imagen).
 *
 * <p><b>LIMITACION DE ENTORNO, NO DE DISENO -- sin probar end-to-end:</b> no
 * hay ninguna API key de Gemini configurada en este entorno (verificado: nada
 * en {@code application.properties} ni en variables de entorno). Esta clase
 * nunca fue ejercitada contra la API real; toda la cobertura de
 * {@code VisionOcrService} usa un fake de {@link VisionGeminiClient} que no
 * pasa por esta implementacion (mismo criterio adoptado para la falta de
 * archivos reales de Sparx EA en UC16 de la oleada anterior). La forma del
 * request/response de abajo sigue la documentacion publica de la API de
 * Gemini, pero no fue validada contra el servicio real.</p>
 */
@Service
public class VisionGeminiClientImpl implements VisionGeminiClient {

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key={key}";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public VisionGeminiClientImpl(
            @Value("${vision.gemini.api-key:}") String apiKey,
            @Value("${vision.gemini.model:gemini-2.0-flash}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.create();
    }

    @Override
    public String generateContent(byte[] imageBytes, String mimeType, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No hay una API key de Gemini configurada (propiedad 'vision.gemini.api-key', "
                            + "resuelta por defecto desde la variable de entorno GEMINI_API_KEY -- ver "
                            + "application.properties). La integracion real con Gemini no se pudo probar "
                            + "end-to-end en este entorno por esta misma razon: es una limitacion de "
                            + "entorno, no de diseno.");
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(
                                Map.of("text", prompt),
                                Map.of("inline_data", Map.of("mime_type", mimeType, "data", base64Image))
                        )
                )),
                "generationConfig", Map.of("responseMimeType", "application/json")
        );

        String url = ENDPOINT_TEMPLATE.formatted(model);
        GeminiGenerateContentResponse response = restClient.post()
                .uri(url, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(GeminiGenerateContentResponse.class);

        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new IllegalStateException("Gemini no devolvio ningun candidate para la imagen enviada");
        }
        GeminiContent content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            throw new IllegalStateException("Gemini devolvio un candidate sin contenido de texto");
        }
        return content.parts().get(0).text();
    }

    // ---- forma real (parcial) de la respuesta de Gemini generateContent ----

    private record GeminiGenerateContentResponse(List<GeminiCandidate> candidates) {
    }

    private record GeminiCandidate(GeminiContent content) {
    }

    private record GeminiContent(List<GeminiPart> parts) {
    }

    private record GeminiPart(String text) {
    }
}
