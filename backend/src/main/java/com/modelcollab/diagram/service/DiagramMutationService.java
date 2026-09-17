package com.modelcollab.diagram.service;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Aplica cada {@link OperationType} del catalogo (seccion 8.1) sobre el
 * {@link CanonicalModel} persistido en {@code diagrams.current_state} (JSONB)
 * y registra el resultado en {@code diagram_operations}. Este es el punto
 * donde las mutaciones STOMP dejan de ser solo un relay (ver Javadoc de
 * {@code CollaborationStompController}) y pasan a tener efecto real en
 * PostgreSQL.
 */
@Service
public class DiagramMutationService {

    private static final Logger log = LoggerFactory.getLogger(DiagramMutationService.class);

    private final DiagramRepository diagramRepository;
    private final DiagramOperationRepository diagramOperationRepository;
    private final CanonicalModelMutator mutator;
    private final ObjectMapper objectMapper;

    public DiagramMutationService(DiagramRepository diagramRepository,
                                   DiagramOperationRepository diagramOperationRepository,
                                   CanonicalModelMutator mutator,
                                   ObjectMapper objectMapper) {
        this.diagramRepository = diagramRepository;
        this.diagramOperationRepository = diagramOperationRepository;
        this.mutator = mutator;
        this.objectMapper = objectMapper;
    }

    /**
     * Aplica la mutacion y la persiste transaccionalmente (nuevo {@code current_state}
     * + fila en {@code diagram_operations}). Devuelve {@code true} si se aplico (y
     * por lo tanto el llamador debe difundirla a la sala), o {@code false} si se
     * rechazo (diagrama inexistente, target inexistente, payload invalido), en cuyo
     * caso no debe difundirse nada.
     *
     * <p>Delega en {@link #applyWithReason} y descarta el motivo de rechazo; se
     * conserva tal cual (en vez de reemplazarla) porque {@code CollaborationStompController}
     * ya la usa y no necesita el motivo (solo informa al emisor un mensaje generico).</p>
     */
    @Transactional
    public boolean apply(UUID diagramId, OperationType type, UUID targetId, UUID userId,
                          long sequenceNum, Map<String, Object> payload) {
        return applyWithReason(diagramId, type, targetId, userId, sequenceNum, payload).applied();
    }

    /**
     * Misma logica que {@link #apply}, pero exponiendo el motivo de rechazo
     * ({@link CanonicalModelMutator.MutationOutcome#reason()}) en vez de colapsarlo
     * a {@code false}. Lo necesita {@code OfflineReconciliationService} (UC12) para
     * armar el {@code discardedDetails} del reporte de reconciliacion offline.
     */
    @Transactional
    public MutationApplyResult applyWithReason(UUID diagramId, OperationType type, UUID targetId, UUID userId,
                                                long sequenceNum, Map<String, Object> payload) {
        // findByIdForUpdate (SELECT ... FOR UPDATE) en vez de findById: serializa el ciclo
        // lectura-modificación-escritura de current_state por diagrama, evitando que dos
        // mutaciones concurrentes sobre el mismo diagrama se pisen (ver Javadoc del metodo).
        Diagram diagram = diagramRepository.findByIdForUpdate(diagramId).orElse(null);
        if (diagram == null) {
            log.warn("Mutacion {} rechazada: el diagrama {} no existe", type, diagramId);
            return MutationApplyResult.rejected("El diagrama " + diagramId + " no existe");
        }

        CanonicalModel currentModel = deserialize(diagram.getCurrentState());
        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(currentModel, type, targetId, payload);
        if (!outcome.applied()) {
            log.debug("Mutacion {} sobre diagrama {} rechazada: {}", type, diagramId, outcome.reason());
            return MutationApplyResult.rejected(outcome.reason());
        }

        long newMutationVersion = diagram.getMutationVersion() + 1;
        CanonicalModel resultModel = outcome.model();
        CanonicalModel newModel = new CanonicalModel(resultModel.schemaVersion(), (int) newMutationVersion,
                resultModel.packages(), resultModel.classes(), resultModel.relationships());

        diagram.setCurrentState(objectMapper.writeValueAsString(newModel));
        diagram.setMutationVersion(newMutationVersion);
        diagramRepository.save(diagram);

        DiagramOperation operationLog = new DiagramOperation(diagramId, userId, type.name(),
                objectMapper.writeValueAsString(payload == null ? Map.of() : payload), null, sequenceNum);
        diagramOperationRepository.save(operationLog);

        return MutationApplyResult.success();
    }

    /**
     * @param applied {@code true} si la mutacion se aplico y persistio
     * @param reason  motivo del rechazo ({@code null} si {@code applied} es {@code true})
     */
    public record MutationApplyResult(boolean applied, String reason) {
        public static MutationApplyResult success() {
            return new MutationApplyResult(true, null);
        }

        public static MutationApplyResult rejected(String reason) {
            return new MutationApplyResult(false, reason);
        }
    }

    private CanonicalModel deserialize(String currentStateJson) {
        if (currentStateJson == null || currentStateJson.isBlank()) {
            return CanonicalModel.empty();
        }
        return objectMapper.readValue(currentStateJson, CanonicalModel.class);
    }
}
