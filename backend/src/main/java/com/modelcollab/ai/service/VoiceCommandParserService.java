package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.GeminiInterpretationResult;
import com.modelcollab.ai.dto.VoiceCommandResponse;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.DiagramMutationService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.service.GridLayoutEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Orquesta UC08 (Modelar por Comando de Voz) y UC09 (Modelar por Comando de Texto):
 * ambos casos de uso comparten este mismo motor porque, para cuando el comando llega
 * aqui, ya es texto plano (ver Javadoc de {@code VoiceCommandRequest}).
 *
 * <p>Flujo (seccion RF-02 del documento de arquitectura):</p>
 * <ol>
 *   <li>Corre {@link GenerateDomainGuardrail} PRIMERO. Si bloquea, no se llama a Gemini
 *   en absoluto (el guardrail es deterministico e independiente de la IA).</li>
 *   <li>Si pasa, arma el contexto ({@link CanonicalModel} actual del diagrama) y llama a
 *   {@link GeminiApiClient#interpretCommand}.</li>
 *   <li>Aplica el tope duro de {@value #MAX_OPERATIONS_PER_COMMAND} operaciones (RF-02.6):
 *   el exceso se descarta, nunca se aplica parcialmente "para intentar cumplir".</li>
 *   <li>Para cada {@code ADD_CLASS} sin posicion explicita, calcula una via
 *   {@link GridLayoutEngine} para que no se superponga con las clases existentes.</li>
 *   <li>Aplica cada operacion via {@link DiagramMutationService#applyWithReason}
 *   (mismo punto de entrada que el canal STOMP y la reconciliacion offline), asignandole
 *   numero de secuencia con {@link SequenceService} y difundiendola a la sala si se
 *   aplico -- el broadcast es "deseable pero no bloqueante" (ver tarea): si falla, se
 *   loguea y se continua, ya que la mutacion ya quedo persistida.</li>
 * </ol>
 */
@Service
public class VoiceCommandParserService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandParserService.class);

    /** Tope duro de seguridad (RF-02.6): sin excepcion, aunque el comando pase el guardrail. */
    public static final int MAX_OPERATIONS_PER_COMMAND = 5;

    private final GenerateDomainGuardrail guardrail;
    private final GeminiApiClient geminiApiClient;
    private final DiagramRepository diagramRepository;
    private final DiagramMutationService diagramMutationService;
    private final SequenceService sequenceService;
    private final GridLayoutEngine gridLayoutEngine;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public VoiceCommandParserService(GenerateDomainGuardrail guardrail,
                                      GeminiApiClient geminiApiClient,
                                      DiagramRepository diagramRepository,
                                      DiagramMutationService diagramMutationService,
                                      SequenceService sequenceService,
                                      GridLayoutEngine gridLayoutEngine,
                                      ObjectMapper objectMapper,
                                      SimpMessagingTemplate messagingTemplate) {
        this.guardrail = guardrail;
        this.geminiApiClient = geminiApiClient;
        this.diagramRepository = diagramRepository;
        this.diagramMutationService = diagramMutationService;
        this.sequenceService = sequenceService;
        this.gridLayoutEngine = gridLayoutEngine;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    public VoiceCommandResponse process(UUID diagramId, String command, UUID userId) {
        GenerateDomainGuardrail.GuardrailResult guardrailResult = guardrail.classify(command);
        if (guardrailResult.blocked()) {
            log.info("Comando rechazado por guardrail GENERATE_DOMAIN en diagrama {}: \"{}\"", diagramId, command);
            return VoiceCommandResponse.guardrailRejected(guardrailResult.message());
        }

        CanonicalModel currentModel = loadModel(diagramId);

        GeminiInterpretationResult interpretation = geminiApiClient.interpretCommand(currentModel, command);
        List<AiOperation> operations = interpretation.operations();

        boolean truncated = operations.size() > MAX_OPERATIONS_PER_COMMAND;
        List<AiOperation> capped = truncated ? operations.subList(0, MAX_OPERATIONS_PER_COMMAND) : operations;
        if (truncated) {
            log.warn("Gemini devolvio {} operaciones para el diagrama {}; se aplican solo las primeras {} "
                    + "(tope duro RF-02.6)", operations.size(), diagramId, MAX_OPERATIONS_PER_COMMAND);
        }

        List<AiOperation> normalized = normalizePositions(currentModel, capped);

        int appliedCount = 0;
        List<String> rejectedReasons = new ArrayList<>();
        for (AiOperation op : normalized) {
            long sequenceNum = sequenceService.next(diagramId);
            DiagramMutationService.MutationApplyResult result = diagramMutationService.applyWithReason(
                    diagramId, op.type(), op.targetId(), userId, sequenceNum, op.payload());
            if (result.applied()) {
                appliedCount++;
                broadcastBestEffort(diagramId, op, userId, sequenceNum);
            } else {
                rejectedReasons.add(op.type() + ": " + result.reason());
            }
        }

        return VoiceCommandResponse.processed(appliedCount, rejectedReasons.size(), truncated, rejectedReasons);
    }

    private CanonicalModel loadModel(UUID diagramId) {
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Diagram not found: " + diagramId));
        String currentStateJson = diagram.getCurrentState();
        if (currentStateJson == null || currentStateJson.isBlank()) {
            return CanonicalModel.empty();
        }
        return objectMapper.readValue(currentStateJson, CanonicalModel.class);
    }

    /**
     * Para cada {@code ADD_CLASS} del lote sin {@code position} explicita en el payload,
     * calcula una posicion via {@link GridLayoutEngine} (RF-02.5) tomando como ocupadas
     * las clases ya existentes en {@code currentModel}. Se calculan todas de una sola vez
     * (no clase por clase) para que dos {@code ADD_CLASS} del mismo comando nunca reciban
     * la misma celda entre si, ademas de no solaparse con las existentes.
     *
     * <p>Limitacion conocida: si otro usuario agrega una clase concurrentemente entre este
     * calculo y la aplicacion efectiva de la mutacion, la posicion calculada aqui podria
     * coincidir con la de esa clase nueva -- {@code DiagramMutationService} no revalida
     * solapamiento de posiciones (no es una invariante que bloquee la mutacion), asi que
     * en el peor caso dos clases quedarian visualmente superpuestas hasta que alguien las
     * mueva. Se acepta este costo: recalcular con lock de diagrama por cada ADD_CLASS
     * individual eliminaria la ventaja de aplicar el lote en una sola pasada.</p>
     */
    private List<AiOperation> normalizePositions(CanonicalModel currentModel, List<AiOperation> operations) {
        long missingPositionCount = operations.stream()
                .filter(op -> op.type() == OperationType.ADD_CLASS && !hasExplicitPosition(op.payload()))
                .count();
        if (missingPositionCount == 0) {
            return operations;
        }

        List<Position> computedPositions = gridLayoutEngine.computePositions(currentModel, (int) missingPositionCount);
        List<AiOperation> result = new ArrayList<>(operations.size());
        int nextPositionIndex = 0;
        for (AiOperation op : operations) {
            if (op.type() == OperationType.ADD_CLASS && !hasExplicitPosition(op.payload())) {
                Position position = computedPositions.get(nextPositionIndex++);
                Map<String, Object> withPosition = new LinkedHashMap<>(op.payload());
                withPosition.put("position", Map.of("x", position.x(), "y", position.y()));
                result.add(new AiOperation(op.type(), op.targetId(), withPosition));
            } else {
                result.add(op);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean hasExplicitPosition(Map<String, Object> payload) {
        Object rawPosition = payload.get("position");
        if (!(rawPosition instanceof Map)) {
            return false;
        }
        Map<String, Object> position = (Map<String, Object>) rawPosition;
        return position.get("x") != null && position.get("y") != null;
    }

    /**
     * Difunde la mutacion recien aplicada a la sala STOMP del diagrama, igual que hace
     * {@code CollaborationStompController} para las mutaciones manuales -- asi un
     * colaborador viendo el lienzo en tiempo real ve aparecer las clases/atributos que
     * la IA acaba de crear, sin recargar. Es "deseable pero no bloqueante" (ver tarea):
     * un fallo aqui no revierte la mutacion, que ya quedo persistida.
     */
    private void broadcastBestEffort(UUID diagramId, AiOperation op, UUID userId, long sequenceNum) {
        try {
            StompBroadcastMessage broadcastMessage = new StompBroadcastMessage(
                    sequenceNum, op.type(), op.targetId(), userId, System.currentTimeMillis(), op.payload());
            messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, broadcastMessage);
        } catch (RuntimeException ex) {
            log.warn("No se pudo difundir a la sala STOMP la operacion {} del diagrama {} generada por IA: {}",
                    op.type(), diagramId, ex.getMessage());
        }
    }
}
