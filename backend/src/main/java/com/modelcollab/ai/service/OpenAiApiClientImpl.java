package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.metamodel.model.CanonicalModel;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Implementacion real de {@link GeminiApiClient} (el nombre del puerto quedo de
 * cuando el proveedor era Gemini -- no se renombro la interfaz ni sus consumidores
 * a proposito, ver migracion Gemini -> OpenAI mas abajo, para no tocar
 * {@code VoiceCommandParserService} ni el resto de las clases que no dependen del
 * proveedor concreto). Llama a la API de Chat Completions de OpenAI
 * ({@code POST /v1/chat/completions}) con el modelo {@code gpt-5.6-luna}, pidiendo
 * un mensaje de sistema con las reglas de interpretacion y uno de usuario con el
 * {@link CanonicalModel} actual + el comando, y {@code response_format:
 * {"type":"json_object"}} (JSON mode de OpenAI) para mantener la misma fiabilidad
 * de parseo que ya tenia el prompt de Gemini con {@code responseMimeType}.
 *
 * <h2>Migracion Gemini -> OpenAI (motivo)</h2>
 * <p>El tier gratuito de Gemini tiene un limite duro de 20 peticiones diarias por
 * proyecto, insuficiente para el volumen de pruebas de este proyecto, y no hay
 * presupuesto para el tier de facturacion. Se reemplazo {@code GeminiApiClientImpl}
 * (eliminada) por esta clase, implementando el mismo puerto {@link GeminiApiClient}
 * sin tocar {@code VoiceCommandParserService} ni el guardrail {@link GenerateDomainGuardrail}
 * -- ninguno de los dos depende del proveedor de IA concreto.</p>
 *
 * <h2>ADVERTENCIA DE ENTORNO -- sin probar end-to-end</h2>
 * <p>Esta clase no fue ejercitada contra la API real de OpenAI en este entorno de
 * desarrollo al momento de escribirla (la key se completa manualmente despues, en
 * {@code application-local.properties}). La forma del request/response implementada
 * aqui sigue la documentacion publica de la API de Chat Completions de OpenAI.</p>
 */
@Service
public class OpenAiApiClientImpl implements GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiApiClientImpl.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAiApiClientImpl(
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.api.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.model:gpt-5.6-luna}") String model,
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
            // credenciales y falle con un 401 confuso.
            throw new IllegalStateException(
                    "No hay OPENAI_API_KEY configurada en este entorno: no se puede invocar a OpenAI. "
                            + "Configure openai.api.key en application.properties o la variable de entorno "
                            + "OPENAI_API_KEY para habilitar el modelado por voz/texto (UC08/UC09).");
        }

        Map<String, Object> requestBody = buildRequestBody(currentModel, command);

        String rawResponse = callWithRetryOn429(() -> restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class));

        return parseResponse(rawResponse);
    }

    /**
     * Arma el body de {@code /chat/completions}. Package-private (no privado) a
     * proposito, igual que {@link #callWithRetryOn429}: para que el test pueda
     * verificar directamente que este payload NUNCA incluye {@code "temperature"}
     * -- ver advertencia real de la API abajo -- sin necesidad de mockear HTTP.
     *
     * <p>OJO -- sin {@code temperature}: confirmado con la API real que
     * {@code gpt-5.6-luna} rechaza con 400 Bad Request cualquier valor de
     * {@code temperature} distinto del default (1) ("Unsupported value: 'temperature'
     * does not support 0.2 with this model. Only the default (1) value is supported.").
     * Gemini si permitia bajarla (0.2) para respuestas mas deterministas; para
     * compensar la falta de ese control aqui, {@link #buildSystemPrompt} refuerza de
     * forma explicita que la salida debe ser UNICAMENTE el JSON pedido. NO reagregar
     * "temperature" a este payload salvo que se confirme un valor soportado por el
     * modelo configurado.</p>
     */
    Map<String, Object> buildRequestBody(CanonicalModel currentModel, String command) {
        return Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", buildSystemPrompt()),
                        Map.of("role", "user", "content", buildUserPrompt(currentModel, command))),
                "response_format", Map.of("type", "json_object"));
    }

    /**
     * Reintento acotado, exclusivo para {@code 429 Too Many Requests} -- equivalente
     * de OpenAI al {@code 503 Service Unavailable} que se manejaba para Gemini (rate
     * limiting/saturacion temporal, no un error del comando ni de la request). Mismo
     * mecanismo que el usado para Gemini: como mucho 2 reintentos (3 intentos totales),
     * 2s y 5s de espera. Cualquier otro codigo de error (4xx u otro 5xx) se propaga de
     * inmediato. Si el tercer intento tambien da 429, se traduce a
     * {@link ResponseStatusException} 503 (no 429: el 503 es la senal que ya entiende
     * el frontend, ver {@code VoiceToolbar.tsx}/{@code VisionModal.tsx}) con un mensaje
     * claro, en vez de dejar que el 429 crudo de OpenAI llegue como un 500 generico.
     */
    String callWithRetryOn429(Supplier<String> call) {
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
     * Mensaje de sistema: rol fijo + catalogo cerrado de operaciones + convencion de
     * payload + formato de salida exigido -- todo lo que no cambia entre comandos.
     *
     * <p>Sin {@code temperature} baja disponible (gpt-5.6-luna solo acepta su default,
     * ver comentario en {@link #interpretCommand}), este prompt es mas explicito e
     * insistente que el original de Gemini sobre el formato exacto de salida --
     * {@code response_format: json_object} ya obliga a que la respuesta sea JSON
     * valido, pero no evita que el modelo agregue prosa DENTRO del JSON (p.ej. en
     * campos de texto) con una temperature mas alta; de ahi el enfasis repetido en
     * "EXCLUSIVAMENTE"/"UNICAMENTE" mas abajo.</p>
     */
    private String buildSystemPrompt() {
        String operationCatalog = String.join(", ",
                java.util.Arrays.stream(OperationType.values()).map(Enum::name).toList());

        return """
                Sos el motor de interpretacion de comandos de modelado UML/ER de ModelCollab.
                Tu unica tarea es traducir UNA orden en lenguaje natural (en espanol) a una lista
                de operaciones atomicas del catalogo cerrado: %s.

                Convencion de payload (igual que CanonicalModelMutator):
                - ADD_CLASS / ADD_ATTRIBUTE / ADD_METHOD / ADD_RELATIONSHIP / ADD_PACKAGE: el
                  payload es el objeto nuevo completo. Genera un UUID v4 nuevo para "id" de cada
                  objeto nuevo (y reutiliza ese mismo id si otra operacion de esta misma respuesta
                  necesita referenciarlo, p.ej. una relacion hacia una clase que tambien estas creando).
                - No incluyas "position" en ADD_CLASS salvo que el comando pida una ubicacion
                  explicita: el servidor calcula la posicion automaticamente con auto-layout.
                - UPDATE_*/RENAME_*/RESIZE_*: el payload trae SOLO los campos que cambian.
                - DELETE_*: el payload puede ir vacio, alcanza con "targetId".

                IMPORTANTE -- limite de seguridad: nunca generes mas de 5 operaciones en total,
                incluso si el comando pareciera pedir mas (el servidor las rechaza de todas formas).

                Responde UNICAMENTE con el JSON solicitado, EXCLUSIVAMENTE con esta forma exacta,
                sin markdown, sin bloques de codigo, sin explicaciones ni ningun texto antes o
                despues del JSON:
                {"operations": [{"type": "ADD_CLASS", "targetId": null, "payload": { ... }}]}
                "targetId" es el UUID del elemento existente afectado, o null si la operacion no
                aplica sobre un elemento existente (p.ej. ADD_CLASS). No agregues comentarios ni
                texto libre dentro de ningun valor del JSON que no haya sido pedido explicitamente
                por el usuario (p.ej. no inventes descripciones en "name").
                """.formatted(operationCatalog);
    }

    /** Mensaje de usuario: lo que sí cambia por comando -- el estado actual del diagrama y la orden. */
    private String buildUserPrompt(CanonicalModel currentModel, String command) {
        String modelJson = objectMapper.writeValueAsString(currentModel);
        return """
                Estado actual del diagrama (CanonicalModel, formato JSON canonico; usa los "id"
                de clases/atributos/relaciones existentes cuando el comando las referencie):
                %s

                Orden del usuario: "%s"
                """.formatted(modelJson, command);
    }

    /**
     * Extrae {@code choices[0].message.content} del envelope de Chat Completions de
     * OpenAI y parsea ese texto interno (el JSON de operaciones pedido en el prompt) --
     * mismo esquema interno que ya se usaba para el texto de Gemini.
     */
    @SuppressWarnings("unchecked")
    private GeminiInterpretationResult parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("Respuesta vacia de OpenAI");
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> envelope = objectMapper.readValue(rawResponse, new TypeReference<Map<String, Object>>() {
        });
        List<Object> choices = (List<Object>) envelope.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.warn("Respuesta de OpenAI sin 'choices': {}", rawResponse);
            return GeminiInterpretationResult.empty();
        }
        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        String innerJson = message == null ? null : (String) message.get("content");
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
