package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.metamodel.model.CanonicalModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementacion real de {@link GeminiApiClient}: llama a la API HTTP de Google Gemini
 * 2.0 Flash ({@code generateContent}) con un prompt estructurado que incluye el
 * {@link CanonicalModel} actual como contexto y le pide que devuelva JSON con la lista
 * de {@link AiOperation} a aplicar.
 *
 * <h2>ADVERTENCIA DE ENTORNO -- sin probar end-to-end</h2>
 * <p><b>Esta clase NUNCA fue ejercitada contra la API real de Gemini en este entorno de
 * desarrollo</b>: no hay ninguna {@code GEMINI_API_KEY} configurada (se verifico que no
 * existe en {@code application.properties} ni en variables de entorno). La forma del
 * request/response implementada aqui (endpoint {@code v1beta/models/{model}:generateContent},
 * cuerpo {@code contents[].parts[].text}, {@code generationConfig.responseMimeType} y la
 * lectura de {@code candidates[0].content.parts[0].text}) sigue la documentacion publica
 * de la API de Gemini, pero solo los tests unitarios con {@link GeminiApiClient} stubeado
 * (ver {@code VoiceCommandParserServiceTest}) verifican el flujo que consume esta clase;
 * la llamada HTTP real en si misma queda sin verificar en este entorno, de forma analoga
 * a como UC16 documenta la ausencia de archivos reales de Sparx EA para probar el import
 * XMI: es una limitacion de entorno, no de diseño. Si en el futuro se configura una API
 * key real, {@link #interpretCommand} deberia poder probarse tal cual sin cambios de
 * codigo (solo configuracion).</p>
 */
@Service
public class GeminiApiClientImpl implements GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiApiClientImpl.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public GeminiApiClientImpl(
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${gemini.api.model:gemini-2.0-flash}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public GeminiInterpretationResult interpretCommand(CanonicalModel currentModel, String command) {
        if (apiKey == null || apiKey.isBlank()) {
            // Fail-fast explicito en vez de dejar que RestClient intente una llamada sin
            // credenciales y falle con un 400/403 confuso: este entorno de desarrollo no
            // tiene GEMINI_API_KEY configurada (ver advertencia de clase). En produccion,
            // configurar gemini.api.key (o la variable de entorno GEMINI_API_KEY) resuelve esto.
            throw new IllegalStateException(
                    "No hay GEMINI_API_KEY configurada en este entorno: no se puede invocar a Gemini. "
                            + "Configure gemini.api.key en application.properties o la variable de entorno "
                            + "GEMINI_API_KEY para habilitar el modelado por voz/texto (UC08/UC09).");
        }

        String prompt = buildPrompt(currentModel, command);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.2));

        String rawResponse = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .queryParam("key", apiKey)
                        .build(model))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseResponse(rawResponse);
    }

    /**
     * Prompt estructurado: contexto (modelo canonico actual serializado tal cual el
     * esquema de la seccion 7 del documento) + catalogo cerrado de {@link OperationType}
     * + convencion de payload (misma que documenta {@code CanonicalModelMutator}) + el
     * comando del usuario + formato de salida exigido (JSON estricto, sin markdown).
     */
    private String buildPrompt(CanonicalModel currentModel, String command) {
        String modelJson = objectMapper.writeValueAsString(currentModel);
        String operationCatalog = String.join(", ",
                java.util.Arrays.stream(OperationType.values()).map(Enum::name).toList());

        return """
                Sos el motor de interpretacion de comandos de modelado UML/ER de ModelCollab.
                Tu unica tarea es traducir UNA orden en lenguaje natural (en espanol) a una lista
                de operaciones atomicas del catalogo cerrado: %s.

                Estado actual del diagrama (CanonicalModel, formato JSON canonico; usa los "id"
                de clases/atributos/relaciones existentes cuando el comando las referencie):
                %s

                Convencion de payload (igual que CanonicalModelMutator):
                - ADD_CLASS / ADD_ATTRIBUTE / ADD_METHOD / ADD_RELATIONSHIP / ADD_PACKAGE: el
                  payload es el objeto nuevo completo. Genera un UUID v4 nuevo para "id" de cada
                  objeto nuevo (y reutiliza ese mismo id si otra operacion de esta misma respuesta
                  necesita referenciarlo, p.ej. una relacion hacia una clase que tambien estas creando).
                - No incluyas "position" en ADD_CLASS salvo que el comando pida una ubicacion
                  explicita: el servidor calcula la posicion automaticamente con auto-layout.
                - UPDATE_*/RENAME_*/RESIZE_*: el payload trae SOLO los campos que cambian.
                - DELETE_*: el payload puede ir vacio, alcanza con "targetId".

                Orden del usuario: "%s"

                IMPORTANTE -- limite de seguridad: nunca generes mas de 5 operaciones en total,
                incluso si el comando pareciera pedir mas (el servidor las rechaza de todas formas).

                Responde EXCLUSIVAMENTE con un JSON (sin markdown, sin texto adicional) con esta forma:
                {"operations": [{"type": "ADD_CLASS", "targetId": null, "payload": { ... }}]}
                "targetId" es el UUID del elemento existente afectado, o null si la operacion no
                aplica sobre un elemento existente (p.ej. ADD_CLASS).
                """.formatted(operationCatalog, modelJson, command);
    }

    /**
     * Extrae {@code candidates[0].content.parts[0].text} del envelope de Gemini y
     * parsea ese texto interno (el JSON de operaciones pedido en el prompt).
     */
    @SuppressWarnings("unchecked")
    private GeminiInterpretationResult parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("Respuesta vacia de Gemini");
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> envelope = objectMapper.readValue(rawResponse, new TypeReference<Map<String, Object>>() {
        });
        List<Object> candidates = (List<Object>) envelope.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            log.warn("Respuesta de Gemini sin 'candidates': {}", rawResponse);
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> firstCandidate = (Map<String, Object>) candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
        List<Object> parts = content == null ? null : (List<Object>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            log.warn("Respuesta de Gemini sin 'content.parts': {}", rawResponse);
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> firstPart = (Map<String, Object>) parts.get(0);
        String innerJson = (String) firstPart.get("text");
        return parseOperations(innerJson);
    }

    @SuppressWarnings("unchecked")
    private GeminiInterpretationResult parseOperations(String innerJson) {
        if (innerJson == null || innerJson.isBlank()) {
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> parsed = objectMapper.readValue(innerJson, new TypeReference<Map<String, Object>>() {
        });
        List<Object> rawOperations = (List<Object>) parsed.get("operations");
        if (rawOperations == null) {
            return GeminiInterpretationResult.empty();
        }
        List<AiOperation> operations = new ArrayList<>();
        for (Object rawOp : rawOperations) {
            Map<String, Object> opMap = (Map<String, Object>) rawOp;
            OperationType type = OperationType.valueOf(String.valueOf(opMap.get("type")));
            Object rawTargetId = opMap.get("targetId");
            UUID targetId = rawTargetId == null ? null : UUID.fromString(rawTargetId.toString());
            Map<String, Object> payload = (Map<String, Object>) opMap.getOrDefault("payload", Map.of());
            operations.add(new AiOperation(type, targetId, payload));
        }
        return new GeminiInterpretationResult(operations);
    }
}
