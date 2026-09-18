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
 * {@link VisionGeminiClient} mockeado -- SIN red, sin necesidad de ninguna API
 * key real (ver el javadoc de {@code OpenAiVisionClientImpl}, la implementacion
 * real de este puerto). El texto JSON fijo usado abajo simula la forma real que
 * le pedimos al proveedor de IA en el prompt de {@link VisionOcrService}.
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
    void analyzeSketch_attributeWithExplicitNullOrOmittedPrimaryKey_isTreatedAsFalse() {
        // Bug real encontrado con Gemini real (no stub): antes de este fix,
        // GeminiDraftAttribute.primaryKey era un boolean primitivo poblado directo
        // del JSON de Gemini via objectMapper.readValue -- un "primaryKey": null
        // explicito (o el campo directamente ausente) hacia fallar la deserializacion
        // completa de GeminiSketchDraft con InvalidNullException, antes de llegar
        // siquiera a Attribute.builder().
        String geminiJson = """
                {
                  "classes": [
                    {
                      "name": "Factura",
                      "existingClassId": null,
                      "attributes": [
                        { "name": "total", "type": "DECIMAL", "primaryKey": null },
                        { "name": "descripcion", "type": "VARCHAR" }
                      ]
                    }
                  ],
                  "relationships": []
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{1}, "image/jpeg", CanonicalModel.empty()));

        assertThat(draft.classes()).hasSize(1);
        ClassEntity factura = draft.classes().get(0);
        assertThat(factura.attributes()).hasSize(2);
        assertThat(factura.attributes()).allSatisfy(a -> assertThat(a.isPrimaryKey()).isFalse());
    }

    @Test
    void analyzeSketch_detectsMethodsWithParameters_compositePatternRealCase() {
        // Reproduce el bug real reportado: una foto real del patron Composite
        // (Component: operation/add/remove/getChild, Leaf: operation,
        // Composite: operation/add/remove/getChild) llegaba con las clases
        // creadas pero sin ningun metodo, porque ni el prompt le pedia
        // "methods" a Gemini ni GeminiDraftClass tenia ese campo -- no era un
        // problema de mapeo, era que el dato nunca se pedia ni se transportaba.
        String geminiJson = """
                {
                  "classes": [
                    {
                      "name": "Component",
                      "existingClassId": null,
                      "attributes": [],
                      "methods": [
                        { "name": "operation", "returnType": "void", "parameters": [] },
                        { "name": "add", "returnType": "void", "parameters": [
                          { "name": "component", "type": "Component" } ] },
                        { "name": "remove", "returnType": "void", "parameters": [
                          { "name": "component", "type": "Component" } ] },
                        { "name": "getChild", "returnType": "Component", "parameters": [
                          { "name": "index", "type": "int" } ] }
                      ]
                    },
                    {
                      "name": "Leaf",
                      "existingClassId": null,
                      "attributes": [],
                      "methods": [
                        { "name": "operation", "returnType": "void", "parameters": [] }
                      ]
                    }
                  ],
                  "relationships": []
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{1}, "image/jpeg", CanonicalModel.empty()));

        ClassEntity component = draft.classes().stream().filter(c -> c.name().equals("Component")).findFirst().orElseThrow();
        ClassEntity leaf = draft.classes().stream().filter(c -> c.name().equals("Leaf")).findFirst().orElseThrow();

        assertThat(component.methods()).extracting(m -> m.name())
                .containsExactlyInAnyOrder("operation", "add", "remove", "getChild");
        assertThat(leaf.methods()).extracting(m -> m.name()).containsExactly("operation");

        var addMethod = component.methods().stream().filter(m -> m.name().equals("add")).findFirst().orElseThrow();
        assertThat(addMethod.parameters()).hasSize(1);
        assertThat(addMethod.parameters().get(0).name()).isEqualTo("component");
        assertThat(addMethod.parameters().get(0).type()).isEqualTo("Component");

        var getChild = component.methods().stream().filter(m -> m.name().equals("getChild")).findFirst().orElseThrow();
        assertThat(getChild.returnType()).isEqualTo("Component");
    }

    @Test
    void analyzeSketch_classWithoutMethodsKeyAtAll_doesNotFail() {
        // El prompt le pide a Gemini que siempre incluya "methods": [] aunque este
        // vacio, pero si de todas formas omite la clave (el modelo no siempre obedece
        // el formato al pie de la letra), no debe romper -- mismo criterio que ya
        // existia para "attributes" ausente.
        String geminiJson = """
                {
                  "classes": [
                    { "name": "SinMetodos", "existingClassId": null, "attributes": [] }
                  ],
                  "relationships": []
                }
                """;
        when(geminiClient.generateContent(any(byte[].class), anyString(), anyString())).thenReturn(geminiJson);

        DraftModelResponse draft = service.analyzeSketch(UUID.randomUUID(),
                new VisionImportRequest(new byte[]{1}, "image/jpeg", CanonicalModel.empty()));

        assertThat(draft.classes()).hasSize(1);
        assertThat(draft.classes().get(0).methods()).isEmpty();
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
