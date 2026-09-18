package com.modelcollab.vision.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre {@code OpenAiVisionClientImpl.callWithRetryOn429} de forma aislada, mismo
 * criterio que {@code OpenAiApiClientImplTest} (ver esa clase para el detalle):
 * se invoca el metodo package-private directo con un {@link Supplier} simulado, sin
 * red, verificando el reintento con backoff ante 429 Too Many Requests (rate
 * limiting de OpenAI -- reemplaza el 503 que se manejaba para Gemini).
 */
class OpenAiVisionClientImplTest {

    private final OpenAiVisionClientImpl client = new OpenAiVisionClientImpl("", "http://localhost", "modelo-test");

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

    private static HttpClientErrorException tooManyRequests() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null);
    }
}
