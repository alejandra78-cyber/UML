package com.modelcollab.diagram.service;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.OfflineSyncRequest.PendingOperation;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitario de {@link OfflineReconciliationService} (UC12, algoritmo de rebase
 * de la seccion 11.2 del documento de arquitectura). No levanta contexto Spring:
 * {@link DiagramMutationService} y {@link SimpMessagingTemplate} se mockean con
 * Mockito (siguiendo el mismo estilo que {@code LockManagerServiceTest}), y
 * {@link SequenceService} se usa real porque es puramente en memoria y barata de
 * instanciar -- asi el test tambien verifica que cada operacion aplicada recibe
 * su propio numero de secuencia monotonico creciente.
 *
 * <p>No se prueba aqui la logica de aceptacion/descarte por tipo de operacion en
 * si misma (eso ya lo cubre {@code CanonicalModelMutatorTest}): {@link DiagramMutationService}
 * se mockea directamente para simular sus veredictos ({@code applied}/{@code rejected}
 * con motivo), y este test se enfoca en el bucle de reconciliacion: orden de
 * procesamiento, extraccion del {@code targetId} reservado dentro del payload,
 * conteo aplicadas/descartadas y el armado de {@code discardedDetails}.</p>
 */
class OfflineReconciliationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DiagramMutationService diagramMutationService;
    private SimpMessagingTemplate messagingTemplate;
    private OfflineReconciliationService service;

    private UUID diagramId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        diagramMutationService = mock(DiagramMutationService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new OfflineReconciliationService(
                diagramMutationService, new SequenceService(), objectMapper, messagingTemplate);

        diagramId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    void moveClass_onExistingClass_isAppliedAndBroadcast() {
        UUID classId = UUID.randomUUID();
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.MOVE_CLASS), eq(classId),
                eq(userId), anyLong(), any())).thenReturn(DiagramMutationService.MutationApplyResult.success());

        OfflineSyncRequest request = requestOf(pendingOp(OperationType.MOVE_CLASS,
                Map.of("targetId", classId.toString(), "x", 340.0, "y", 260.0)));

        ValidationReportDTO report = service.reconcile(diagramId, userId, request);

        assertThat(report.appliedCount()).isEqualTo(1);
        assertThat(report.discardedCount()).isZero();
        assertThat(report.discardedDetails()).isEmpty();
        verify(diagramMutationService).applyWithReason(eq(diagramId), eq(OperationType.MOVE_CLASS), eq(classId),
                eq(userId), anyLong(), eq(Map.of("x", 340.0, "y", 260.0)));
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId), any(Object.class));
    }

    @Test
    void addAttribute_onDeletedClass_isDiscardedWithReadableMessage() {
        UUID classId = UUID.randomUUID();
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_ATTRIBUTE), eq(classId),
                eq(userId), anyLong(), any())).thenReturn(
                DiagramMutationService.MutationApplyResult.rejected("Clase no encontrada: " + classId));

        OfflineSyncRequest request = requestOf(pendingOp(OperationType.ADD_ATTRIBUTE,
                Map.of("targetId", classId.toString(), "id", UUID.randomUUID().toString(),
                        "name", "telefono", "type", "VARCHAR", "visibility", "PRIVATE")));

        ValidationReportDTO report = service.reconcile(diagramId, userId, request);

        assertThat(report.appliedCount()).isZero();
        assertThat(report.discardedCount()).isEqualTo(1);
        assertThat(report.discardedDetails()).hasSize(1);
        assertThat(report.discardedDetails().get(0))
                .contains("ADD_ATTRIBUTE")
                .contains("'telefono'")
                .contains("Clase no encontrada: " + classId);
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void deleteClass_alwaysWins_isApplied() {
        UUID classId = UUID.randomUUID();
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.DELETE_CLASS), eq(classId),
                eq(userId), anyLong(), any())).thenReturn(DiagramMutationService.MutationApplyResult.success());

        OfflineSyncRequest request = requestOf(
                pendingOp(OperationType.DELETE_CLASS, Map.of("targetId", classId.toString())));

        ValidationReportDTO report = service.reconcile(diagramId, userId, request);

        assertThat(report.appliedCount()).isEqualTo(1);
        assertThat(report.discardedCount()).isZero();
    }

    @Test
    void mixedQueue_countsAppliedAndDiscardedCorrectly() {
        UUID movedClassId = UUID.randomUUID();
        UUID deletedClassId = UUID.randomUUID();
        UUID newClassId = UUID.randomUUID();
        UUID renamedClassId = UUID.randomUUID();
        UUID updatedAttributeId = UUID.randomUUID();
        UUID deletedMethodId = UUID.randomUUID();

        // 1) MOVE_CLASS sobre clase existente -> aplicada
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.MOVE_CLASS), eq(movedClassId),
                eq(userId), anyLong(), any())).thenReturn(DiagramMutationService.MutationApplyResult.success());
        // 2) ADD_ATTRIBUTE sobre clase eliminada -> descartada
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_ATTRIBUTE),
                eq(deletedClassId), eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.rejected(
                        "Clase no encontrada: " + deletedClassId));
        // 3) ADD_CLASS nueva -> aplicada
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.ADD_CLASS), isNull(),
                eq(userId), anyLong(), any())).thenReturn(DiagramMutationService.MutationApplyResult.success());
        // 4) DELETE_CLASS -> siempre aplicada
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.DELETE_CLASS),
                eq(renamedClassId), eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.success());
        // 5) UPDATE_ATTRIBUTE sobre atributo eliminado -> descartada
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.UPDATE_ATTRIBUTE),
                eq(updatedAttributeId), eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.rejected(
                        "Atributo no encontrado: " + updatedAttributeId));
        // 6) DELETE_METHOD sobre metodo ya eliminado -> descartada (delete idempotente rechazado por no encontrado)
        when(diagramMutationService.applyWithReason(eq(diagramId), eq(OperationType.DELETE_METHOD),
                eq(deletedMethodId), eq(userId), anyLong(), any()))
                .thenReturn(DiagramMutationService.MutationApplyResult.rejected(
                        "Metodo no encontrado: " + deletedMethodId));

        OfflineSyncRequest request = requestOf(
                pendingOp(OperationType.MOVE_CLASS, Map.of("targetId", movedClassId.toString(), "x", 10.0, "y", 20.0)),
                pendingOp(OperationType.ADD_ATTRIBUTE, Map.of("targetId", deletedClassId.toString(),
                        "id", UUID.randomUUID().toString(), "name", "telefono", "type", "VARCHAR",
                        "visibility", "PRIVATE")),
                pendingOp(OperationType.ADD_CLASS, Map.of("id", newClassId.toString(), "name", "Pedido",
                        "visibility", "PUBLIC", "isAbstract", false)),
                pendingOp(OperationType.DELETE_CLASS, Map.of("targetId", renamedClassId.toString())),
                pendingOp(OperationType.UPDATE_ATTRIBUTE, Map.of("targetId", updatedAttributeId.toString(),
                        "name", "email")),
                pendingOp(OperationType.DELETE_METHOD, Map.of("targetId", deletedMethodId.toString())));

        ValidationReportDTO report = service.reconcile(diagramId, userId, request);

        assertThat(report.appliedCount()).isEqualTo(3);
        assertThat(report.discardedCount()).isEqualTo(3);
        assertThat(report.discardedDetails()).hasSize(3);
        verify(messagingTemplate, times(3)).convertAndSend(eq("/topic/diagrams/" + diagramId), any(Object.class));
    }

    @Test
    void unknownOperationType_isDiscardedWithoutCallingMutationService() {
        OfflineSyncRequest request = requestOf(pendingOp("NOT_A_REAL_OPERATION", Map.of()));

        ValidationReportDTO report = service.reconcile(diagramId, userId, request);

        assertThat(report.appliedCount()).isZero();
        assertThat(report.discardedCount()).isEqualTo(1);
        assertThat(report.discardedDetails().get(0)).contains("NOT_A_REAL_OPERATION");
    }

    private OfflineSyncRequest requestOf(PendingOperation... operations) {
        return new OfflineSyncRequest(List.of(operations));
    }

    private PendingOperation pendingOp(OperationType type, Map<String, Object> payload) {
        return pendingOp(type.name(), payload);
    }

    private PendingOperation pendingOp(String type, Map<String, Object> payload) {
        String payloadJson = objectMapper.writeValueAsString(payload);
        return new PendingOperation(UUID.randomUUID().toString(), type, payloadJson, OffsetDateTime.now());
    }
}
