package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.ai.dto.VoiceCommandResponse;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.DiagramMutationService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.GridLayoutEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitario de {@link VoiceCommandParserService} (UC08/UC09) con
 * {@link GeminiApiClient} <b>fakeado con Mockito</b> (sin red): esta suite nunca llama
 * a la API real de Gemini, siguiendo el mismo estilo sin contexto Spring que
 * {@code OfflineReconciliationServiceTest} para {@link DiagramMutationService}.
 *
 * <p>{@link GenerateDomainGuardrail}, {@link SequenceService} y {@link GridLayoutEngine}
 * se usan reales (son logica pura, baratos de instanciar) para verificar la integracion
 * real del guardrail y del auto-layout, no solo mocks devolviendo lo que el test quiere.</p>
 */
class VoiceCommandParserServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GeminiApiClient geminiApiClient;
    private DiagramRepository diagramRepository;
    private DiagramMutationService diagramMutationService;
    private SimpMessagingTemplate messagingTemplate;
    private VoiceCommandParserService service;

    private UUID diagramId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        geminiApiClient = mock(GeminiApiClient.class);
        diagramRepository = mock(DiagramRepository.class);
        diagramMutationService = mock(DiagramMutationService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        service = new VoiceCommandParserService(
                new GenerateDomainGuardrail(),
                geminiApiClient,
                diagramRepository,
                diagramMutationService,
                new SequenceService(),
                new GridLayoutEngine(),
                objectMapper,
                messagingTemplate);

        diagramId = UUID.randomUUID();
        userId = UUID.randomUUID();

        String emptyStateJson = objectMapper.writeValueAsString(CanonicalModel.empty());
        Diagram diagram = new Diagram(UUID.randomUUID(), "Diagrama de prueba", "1.0.0", 1, emptyStateJson);
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(diagram));
    }

    @Test
    void process_genericDomainCommand_isRejectedByGuardrailWithoutCallingGeminiOrMutating() {
        VoiceCommandResponse response = service.process(diagramId, "Hazme un sistema bancario", userId);

        assertThat(response.guardrailRejected()).isTrue();
        assertThat(response.guardrailMessage()).isEqualTo(GenerateDomainGuardrail.REJECTION_MESSAGE);
        assertThat(response.appliedCount()).isZero();
        assertThat(response.rejectedCount()).isZero();

        verifyNoInteractions(geminiApiClient);
        verifyNoInteractions(diagramMutationService);
        verifyNoInteractions(diagramRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void process_pointedCommand_appliesAddClassWithComputedPositionAndCountsRejection() {
        UUID newClassId = UUID.randomUUID();
        AiOperation addClass = new AiOperation(OperationType.ADD_CLASS, null, Map.of(
                "id", newClassId.toString(), "name", "Cliente", "visibility", "PUBLIC", "isAbstract", false,
                "width", 240.0, "height", 180.0, "attributes", List.of(), "methods", List.of()));

        UUID missingClassId = UUID.randomUUID();
        AiOperation addAttribute = new AiOperation(OperationType.ADD_ATTRIBUTE, missingClassId, Map.of(
                "id", UUID.randomUUID().toString(), "name", "telefono", "type", "VARCHAR", "visibility", "PRIVATE"));

        when(geminiApiClient.interpretCommand(any(CanonicalModel.class), anyString()))
                .thenReturn(new GeminiInterpretationResult(List.of(addClass, addAttribute)));
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_CLASS), isNull(),
                eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.success());
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_ATTRIBUTE), eq(missingClassId),
                eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.rejected("Clase no encontrada: " + missingClassId));

        VoiceCommandResponse response = service.process(
                diagramId, "Crea la clase Cliente y agrega el atributo telefono a Proveedor", userId);

        assertThat(response.guardrailRejected()).isFalse();
        assertThat(response.appliedCount()).isEqualTo(1);
        assertThat(response.rejectedCount()).isEqualTo(1);
        assertThat(response.truncated()).isFalse();
        assertThat(response.rejectedReasons()).hasSize(1);
        assertThat(response.rejectedReasons().get(0))
                .contains("ADD_ATTRIBUTE")
                .contains("Clase no encontrada: " + missingClassId);

        // El payload no traia "position": VoiceCommandParserService debe haberla calculado
        // con GridLayoutEngine (modelo vacio -> primera celda de la grilla, (0,0)).
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(diagramMutationService).applyWithReason(eq(diagramId), eq(OperationType.ADD_CLASS), isNull(),
                eq(userId), anyLong(), payloadCaptor.capture());
        Map<String, Object> capturedPayload = payloadCaptor.getValue();
        assertThat(capturedPayload).containsKey("position");
        Map<String, Object> position = (Map<String, Object>) capturedPayload.get("position");
        assertThat(position.get("x")).isEqualTo(0.0);
        assertThat(position.get("y")).isEqualTo(0.0);

        // Broadcast "deseable pero no bloqueante": solo la operacion aplicada se difunde.
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/diagrams/" + diagramId), any(Object.class));
    }

    @Test
    void process_geminiReturnsMoreThanFiveOperations_hardCapTruncatesToFive() {
        List<AiOperation> sevenOperations = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sevenOperations.add(new AiOperation(OperationType.ADD_CLASS, null, Map.of(
                    "id", UUID.randomUUID().toString(), "name", "Clase" + i, "visibility", "PUBLIC",
                    "isAbstract", false, "width", 240.0, "height", 180.0,
                    "attributes", List.of(), "methods", List.of())));
        }
        when(geminiApiClient.interpretCommand(any(CanonicalModel.class), anyString()))
                .thenReturn(new GeminiInterpretationResult(sevenOperations));
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_CLASS), isNull(),
                eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.success());

        VoiceCommandResponse response = service.process(
                diagramId, "Crea las clases A, B, C, D, E, F y G", userId);

        assertThat(response.guardrailRejected()).isFalse();
        assertThat(response.truncated()).isTrue();
        assertThat(response.appliedCount()).isEqualTo(VoiceCommandParserService.MAX_OPERATIONS_PER_COMMAND);
        assertThat(response.rejectedCount()).isZero();

        verify(diagramMutationService, times(VoiceCommandParserService.MAX_OPERATIONS_PER_COMMAND))
                .applyWithReason(eq(diagramId), eq(OperationType.ADD_CLASS), isNull(), eq(userId), anyLong(), any());
        // Nunca se intenta aplicar la 6ta u 7ma: el tope corta antes de llegar al bucle de aplicacion.
        verify(diagramMutationService, times(5)).applyWithReason(
                eq(diagramId), eq(OperationType.ADD_CLASS), isNull(), eq(userId), anyLong(), any());
        verify(geminiApiClient, times(1)).interpretCommand(any(), anyString());
        verify(diagramMutationService, never()).applyWithReason(
                eq(diagramId), eq(OperationType.DELETE_CLASS), any(), any(), anyLong(), any());
    }
}
