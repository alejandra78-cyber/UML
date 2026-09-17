package com.modelcollab.vision.service;

import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.service.GridLayoutEngine;
import com.modelcollab.vision.dto.DraftModelResponse;
import com.modelcollab.vision.dto.VisionImportRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cubre UC10 (Importar Diagrama desde Foto de Pizarra) con
 * {@link VisionGeminiClient} mockeado -- SIN red, ya que no hay ninguna API
 * key de Gemini configurada en este entorno (ver el javadoc de
 * {@link VisionGeminiClientImpl}). El texto JSON fijo usado abajo simula la
 * forma real que le pedimos a Gemini en el prompt de {@link VisionOcrService}.
 */
class VisionOcrServiceTest {

    private final VisionGeminiClient geminiClient = mock(VisionGeminiClient.class);
    private final GridLayoutEngine gridLayoutEngine = new GridLayoutEngine();
    private final VisionOcrService service = new VisionOcrService(geminiClient, gridLayoutEngine);

    @Test
    void analyzeSketch_detectsNewClassesAttributesAndRelationship_andAssignsGridPositions() {
        String geminiJson = """
                {
                  "classes": [
                    {
                      "name": "Cliente",
                      "existingClassId": null,
                      "attributes": [
                        { "name": "id", "type": "UUID", "primaryKey": true },
                        { "name": "nombre", "type": "VARCHAR", "primaryKey": false }
                      ]
                    },
                    {
                      "name": "Pedido",
                      "existingClassId": null,
                      "attributes": [
                        { "name": "id", "type": "UUID", "primaryKey": true },
                        { "name": "fecha", "type": "DATE", "primaryKey": false }
                      ]
                    }
                  ],
                  "relationships": [
                    {
                      "sourceClassName": "Cliente",
                      "targetClassName": "Pedido",
                      "type": "ASSOCIATION",
                      "sourceMultiplicity": "1..1",
                      "targetMultiplicity": "0..*"
                    }
                  ]
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        CanonicalModel currentModel = CanonicalModel.empty();
        VisionImportRequest request = new VisionImportRequest(new byte[]{1, 2, 3}, "image/jpeg", currentModel);
        UUID diagramId = UUID.randomUUID();

        DraftModelResponse draft = service.analyzeSketch(diagramId, request);

        assertThat(draft.diagramId()).isEqualTo(diagramId);
        assertThat(draft.classes()).hasSize(2);
        assertThat(draft.matchedExistingClassIds()).isEmpty();
        assertThat(draft.warnings()).isEmpty();

        ClassEntity cliente = draft.classes().stream().filter(c -> c.name().equals("Cliente")).findFirst().orElseThrow();
        ClassEntity pedido = draft.classes().stream().filter(c -> c.name().equals("Pedido")).findFirst().orElseThrow();
        assertThat(cliente.attributes()).hasSize(2);
        assertThat(cliente.attributes().stream().anyMatch(a -> a.name().equals("id") && a.isPrimaryKey())).isTrue();

        // GridLayoutEngine debe haber asignado posiciones distintas y no (0,0)+(0,0) por default,
        // ya que ambas clases nuevas caen en celdas distintas de la grilla.
        assertThat(cliente.position()).isNotEqualTo(pedido.position());

        assertThat(draft.relationships()).hasSize(1);
        var relationship = draft.relationships().get(0);
        assertThat(relationship.sourceClassId()).isEqualTo(cliente.id());
        assertThat(relationship.targetClassId()).isEqualTo(pedido.id());
    }

    @Test
    void analyzeSketch_matchesExistingClassByExplicitId_doesNotDuplicateIt() {
        UUID existingClienteId = UUID.randomUUID();
        ClassEntity existingCliente = ClassEntity.builder()
                .id(existingClienteId)
                .name("Cliente")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .build();
        CanonicalModel currentModel = new CanonicalModel("1.0.0", 1, List.of(), List.of(existingCliente), List.of());

        String geminiJson = """
                {
                  "classes": [
                    { "name": "Cliente", "existingClassId": "%s", "attributes": [] },
                    { "name": "Factura", "existingClassId": null, "attributes": [] }
                  ],
                  "relationships": [
                    { "sourceClassName": "Cliente", "targetClassName": "Factura", "type": "ASSOCIATION",
                      "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*" }
                  ]
                }
                """.formatted(existingClienteId);
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{9}, "image/png", currentModel));

        assertThat(draft.matchedExistingClassIds()).containsExactly(existingClienteId);
        assertThat(draft.classes()).hasSize(1);
        assertThat(draft.classes().get(0).name()).isEqualTo("Factura");

        // La relacion detectada debe resolver el extremo "Cliente" contra el id EXISTENTE,
        // no crear una clase Cliente nueva/duplicada.
        assertThat(draft.relationships()).hasSize(1);
        assertThat(draft.relationships().get(0).sourceClassId()).isEqualTo(existingClienteId);
        assertThat(draft.relationships().get(0).targetClassId()).isEqualTo(draft.classes().get(0).id());
    }

    @Test
    void analyzeSketch_relationshipWithUnresolvableEndpoint_isDroppedWithWarning() {
        String geminiJson = """
                {
                  "classes": [
                    { "name": "Cliente", "existingClassId": null, "attributes": [] }
                  ],
                  "relationships": [
                    { "sourceClassName": "Cliente", "targetClassName": "NoExiste", "type": "ASSOCIATION",
                      "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*" }
                  ]
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{7}, "image/jpeg", CanonicalModel.empty()));

        assertThat(draft.relationships()).isEmpty();
        assertThat(draft.warnings()).hasSize(1);
    }

    @Test
    void analyzeSketch_unsupportedMimeType_throwsWithoutCallingGemini() {
        VisionImportRequest request = new VisionImportRequest(new byte[]{1}, "image/gif", CanonicalModel.empty());

        try {
            service.analyzeSketch(UUID.randomUUID(), request);
            org.junit.jupiter.api.Assertions.fail("Se esperaba IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        org.mockito.Mockito.verifyNoInteractions(geminiClient);
    }

    @Test
    void analyzeSketch_toBulkMergePayload_hasExactBulkMergeShape() {
        String geminiJson = """
                {
                  "classes": [ { "name": "Producto", "existingClassId": null, "attributes": [] } ],
                  "relationships": []
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{1}, "image/jpeg", CanonicalModel.empty()));

        var payload = draft.toBulkMergePayload();

        assertThat(payload).containsOnlyKeys("classes", "relationships", "packages");
        assertThat(payload.get("classes")).isEqualTo(draft.classes());
        assertThat(payload.get("relationships")).isEqualTo(draft.relationships());
        assertThat((List<?>) payload.get("packages")).isEmpty();
    }
}
