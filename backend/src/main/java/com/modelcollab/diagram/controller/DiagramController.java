package com.modelcollab.diagram.controller;

import com.modelcollab.collaboration.interceptor.ProjectRoleLookup;
import com.modelcollab.diagram.dto.DiagramRequest;
import com.modelcollab.diagram.dto.DiagramResponse;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.SnapshotResponse;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.OfflineReconciliationService;
import com.modelcollab.metamodel.model.CanonicalModel;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Implementa los endpoints de diagramas de la seccion 14 que faltaban:
 * creacion y snapshot. Reutiliza {@link ProjectRoleLookup} -- el mismo
 * componente que consulta {@code StompChannelInterceptor} para bloquear
 * VIEWER en la colaboracion en tiempo real -- para que la regla de rol sea
 * exactamente la misma en ambos caminos (REST y STOMP), no una reimplementacion
 * paralela que pueda desincronizarse.
 *
 * <p>Regla de acceso: crear un diagrama requiere rol {@code OWNER} o
 * {@code EDITOR} en el proyecto; leer el snapshot solo requiere ser miembro
 * del proyecto (cualquier rol, incluido {@code VIEWER}).</p>
 */
@RestController
public class DiagramController {

    private final DiagramRepository diagramRepository;
    private final ProjectRoleLookup projectRoleLookup;
    private final ObjectMapper objectMapper;
    private final OfflineReconciliationService offlineReconciliationService;

    public DiagramController(DiagramRepository diagramRepository, ProjectRoleLookup projectRoleLookup,
                              ObjectMapper objectMapper, OfflineReconciliationService offlineReconciliationService) {
        this.diagramRepository = diagramRepository;
        this.projectRoleLookup = projectRoleLookup;
        this.objectMapper = objectMapper;
        this.offlineReconciliationService = offlineReconciliationService;
    }

    @PostMapping("/api/v1/projects/{projectId}/diagrams")
    public ResponseEntity<DiagramResponse> createDiagram(@PathVariable UUID projectId,
                                                           @Valid @RequestBody DiagramRequest request,
                                                           @AuthenticationPrincipal UUID userId) {
        requireEditorOrOwner(projectId, userId);

        String initialState = objectMapper.writeValueAsString(CanonicalModel.empty());
        Diagram diagram = new Diagram(projectId, request.name(), "1.0.0", 1, initialState);
        diagram = diagramRepository.save(diagram);

        return ResponseEntity.status(HttpStatus.CREATED).body(DiagramResponse.from(diagram));
    }

    @GetMapping("/api/v1/projects/{projectId}/diagrams")
    public List<DiagramResponse> listDiagrams(@PathVariable UUID projectId, @AuthenticationPrincipal UUID userId) {
        requireAnyMember(projectId, userId);
        return diagramRepository.findByProjectIdOrderByIdAsc(projectId).stream().map(DiagramResponse::from).toList();
    }

    @GetMapping("/api/v1/diagrams/{diagramId}/snapshot")
    public SnapshotResponse getSnapshot(@PathVariable UUID diagramId, @AuthenticationPrincipal UUID userId) {
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));

        requireAnyMember(diagram.getProjectId(), userId);

        return new SnapshotResponse(diagram.getId(), diagram.getCurrentState());
    }

    /**
     * UC12 (Sincronizar Cambios al Reconectar): rebase de la cola offline de un
     * cliente contra el estado actual del diagrama (seccion 11.2 del documento de
     * arquitectura). Misma regla de rol que {@link #createDiagram}: OWNER/EDITOR,
     * ya que reconciliar puede aplicar mutaciones reales sobre el diagrama.
     */
    @PostMapping("/api/v1/diagrams/{diagramId}/sync-offline")
    public ValidationReportDTO syncOffline(@PathVariable UUID diagramId,
                                            @Valid @RequestBody OfflineSyncRequest request,
                                            @AuthenticationPrincipal UUID userId) {
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));

        requireEditorOrOwner(diagram.getProjectId(), userId);

        return offlineReconciliationService.reconcile(diagramId, userId, request);
    }

    private void requireEditorOrOwner(UUID projectId, UUID userId) {
        String role = requireMemberRole(projectId, userId);
        if (!"OWNER".equalsIgnoreCase(role) && !"EDITOR".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo OWNER o EDITOR pueden crear diagramas en este proyecto");
        }
    }

    private void requireAnyMember(UUID projectId, UUID userId) {
        requireMemberRole(projectId, userId);
    }

    /**
     * Devuelve el rol del usuario en el proyecto, o rechaza con 403 si no es
     * miembro (deliberadamente no distingue "proyecto inexistente" de "no soy
     * miembro": ambos casos devuelven 403 sin revelar si el proyecto existe).
     */
    private String requireMemberRole(UUID projectId, UUID userId) {
        return projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
    }
}
