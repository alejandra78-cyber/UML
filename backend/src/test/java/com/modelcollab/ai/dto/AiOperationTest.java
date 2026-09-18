package com.modelcollab.ai.dto;

import com.modelcollab.collaboration.dto.OperationType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Bug real encontrado con Gemini real (no stub): {@code AiOperation} usaba
 * {@code Map.copyOf(payload)} en su constructor compacto, y {@code Map.copyOf}
 * (igual que {@code Map.of}) rechaza explícitamente valores {@code null} con
 * {@code NullPointerException} -- incluso antes de que
 * {@code CanonicalModelMutator.withDefaults} llegara a ejecutarse. Un
 * {@code ADD_CLASS} real de Gemini con {@code "width": null} (campo opcional
 * omitido por el modelo) rompía la construcción del {@code AiOperation} en la
 * capa de parseo ({@code GeminiApiClientImpl.parseOperations}), un paso antes
 * de llegar siquiera a {@code DiagramMutationService}.
 */
class AiOperationTest {

    @Test
    void constructor_toleratesExplicitNullValuesInPayload() {
        Map<String, Object> payloadWithNull = new HashMap<>();
        payloadWithNull.put("id", UUID.randomUUID().toString());
        payloadWithNull.put("name", "Factura");
        payloadWithNull.put("width", null);
        payloadWithNull.put("height", null);

        AiOperation operation = new AiOperation(OperationType.ADD_CLASS, null, payloadWithNull);

        assertThat(operation.payload()).containsEntry("width", null);
        assertThat(operation.payload()).containsEntry("height", null);
        assertThat(operation.payload()).containsEntry("name", "Factura");
    }

    @Test
    void constructor_withNullPayload_doesNotThrow_defaultsToEmptyMap() {
        assertThatCode(() -> new AiOperation(OperationType.ADD_CLASS, null, null)).doesNotThrowAnyException();
        assertThat(new AiOperation(OperationType.ADD_CLASS, null, null).payload()).isEmpty();
    }

    @Test
    void payload_isImmutable() {
        Map<String, Object> mutablePayload = new HashMap<>(Map.of("name", "Cliente"));
        AiOperation operation = new AiOperation(OperationType.ADD_CLASS, null, mutablePayload);

        assertThatCode(() -> operation.payload().put("otro", "valor"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
