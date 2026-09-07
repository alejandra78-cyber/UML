package com.example.demo.diagram.service;

import com.example.demo.collaboration.dto.OperationType;
import com.example.demo.diagram.model.Diagram;
import com.example.demo.diagram.model.DiagramOperation;
import com.example.demo.diagram.repository.DiagramOperationRepository;
import com.example.demo.diagram.repository.DiagramRepository;
import com.example.demo.metamodel.model.CanonicalModel;
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
     */
    @Transactional
    public boolean apply(UUID diagramId, OperationType type, UUID targetId, UUID userId,
                          long sequenceNum, Map<String, Object> payload) {
        Diagram diagram = diagramRepository.findById(diagramId).orElse(null);
        if (diagram == null) {
            log.warn("Mutacion {} rechazada: el diagrama {} no existe", type, diagramId);
            return false;
        }

        CanonicalModel currentModel = deserialize(diagram.getCurrentState());
        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(currentModel, type, targetId, payload);
        if (!outcome.applied()) {
            log.debug("Mutacion {} sobre diagrama {} rechazada: {}", type, diagramId, outcome.reason());
            return false;
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

        return true;
    }

    private CanonicalModel deserialize(String currentStateJson) {
        if (currentStateJson == null || currentStateJson.isBlank()) {
            return CanonicalModel.empty();
        }
        return objectMapper.readValue(currentStateJson, CanonicalModel.class);
    }
}
