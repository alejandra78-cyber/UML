package com.modelcollab.ai.service;

import com.modelcollab.metamodel.model.CanonicalModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre {@code OpenAiApiClientImpl.callWithRetryOn429} de forma aislada -- sin red
 * real, invocando el metodo package-private directo con un {@link Supplier} que
 * simula la secuencia de fallos/exito, mismo criterio que se usaba para
 * {@code GeminiApiClientImplRetryTest} (eliminada al migrar de Gemini a OpenAI) pero
 * contra el codigo de rate limiting real de OpenAI: 429 Too Many Requests, no 503.
 */
class OpenAiApiClientImplTest {

    private final OpenAiApiClientImpl client = new OpenAiApiClientImpl("", "http://localhost", "modelo-test",
            new ObjectMapper());

    @Test
    void callWithRetryOn429_succeedsAfterOne429_waitsAndRetries() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> call = () -> {
            if (attempts.getAndIncrement() == 0) {
                throw tooManyRequests();
            }
            return "ok";
        };

        long start = System.currentTimeMillis();
        String result = client.callWithRetryOn429(call);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(elapsedMs).as("debe esperar ~2s antes del reintento").isGreaterThanOrEqualTo(1900L);
    }

    @Test
    void callWithRetryOn429_succeedsAfterTwo429s_waits2sThen5s() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> call = () -> {
            if (attempts.getAndIncrement() < 2) {
                throw tooManyRequests();
            }
            return "ok";
        };

        long start = System.currentTimeMillis();
        String result = client.callWithRetryOn429(call);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(elapsedMs).as("debe esperar ~2s + ~5s = ~7s en total").isGreaterThanOrEqualTo(6900L);
    }

    @Test
    void callWithRetryOn429_exhaustsRetries_throwsClearResponseStatusException503() {
        // Nunca mas de 2 reintentos (3 intentos totales) -- si el tercero tambien
        // da 429, no se cuelga la request del usuario indefinidamente. El status que
        // ve el frontend es 503 (no 429): es la senal que ya entienden VoiceToolbar.tsx
        // y VisionModal.tsx para "proveedor de IA temporalmente saturado".
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> call = () -> {
            attempts.incrementAndGet();
            throw tooManyRequests();
        };

        assertThatThrownBy(() -> client.callWithRetryOn429(call))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(rse.getReason())
                            .isEqualTo("OpenAI está temporalmente saturado, probá de nuevo en unos minutos");
                });
        assertThat(attempts.get()).as("3 intentos totales: el original + 2 reintentos, ni uno mas").isEqualTo(3);
    }

    @Test
    void callWithRetryOn429_nonRateLimitError_propagatesImmediatelyWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> call = () -> {
            attempts.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal Server Error", null, null, null);
        };

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.callWithRetryOn429(call)).isInstanceOf(HttpServerErrorException.class);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(attempts.get()).as("un error que no es 429 no debe reintentarse").isEqualTo(1);
        assertThat(elapsedMs).as("no debe esperar nada si no es 429").isLessThan(500L);
    }

    @Test
    void callWithRetryOn429_unauthorizedError_propagatesImmediatelyWithoutRetrying() {
        // 401 (API key invalida/faltante en el proveedor) tampoco debe reintentarse --
        // solo 429 dispara el backoff.
        AtomicInteger attempts = new AtomicInteger();
        Supplier<String> call = () -> {
            attempts.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null);
        };

        assertThatThrownBy(() -> client.callWithRetryOn429(call)).isInstanceOf(HttpClientErrorException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void buildRequestBody_neverIncludesTemperature() {
        // Bug real encontrado con la API real de OpenAI: gpt-5.6-luna responde 400
        // Bad Request ante cualquier "temperature" distinto de su default (1)
        // ("Unsupported value: 'temperature' does not support 0.2 with this model.
        // Only the default (1) value is supported."). Este test falla si alguien
        // vuelve a agregar "temperature" al payload con un valor distinto de 1.
        Map<String, Object> requestBody = client.buildRequestBody(CanonicalModel.empty(), "Crea la clase Factura");

        if (requestBody.containsKey("temperature")) {
            assertThat(requestBody.get("temperature"))
                    .as("si se incluye 'temperature', el unico valor que gpt-5.6-luna acepta es 1")
                    .isEqualTo(1);
        }
    }

    @Test
    void buildRequestBody_usesConfiguredModelAndJsonMode() {
        Map<String, Object> requestBody = client.buildRequestBody(CanonicalModel.empty(), "Crea la clase Factura");

        assertThat(requestBody.get("model")).isEqualTo("modelo-test");
        assertThat(requestBody.get("response_format")).isEqualTo(Map.of("type", "json_object"));
    }

    private static HttpClientErrorException tooManyRequests() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
    }
}
