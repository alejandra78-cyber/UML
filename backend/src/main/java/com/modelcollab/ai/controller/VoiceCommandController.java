package com.modelcollab.ai.controller;

import com.modelcollab.ai.dto.VoiceCommandRequest;
import com.modelcollab.ai.dto.VoiceCommandResponse;
import com.modelcollab.ai.service.VoiceCommandParserService;
import com.modelcollab.collaboration.interceptor.ProjectRoleLookup;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Endpoint REST de UC08 (Modelar por Comando de Voz) y UC09 (Modelar por Comando de
 * Texto): {@code POST /api/v1/diagrams/{diagramId}/ai-command} (seccion 14 del documento
 * de arquitectura, fila "AI Voice/Text"). Voz y texto llegan aqui exactamente igual: la
 * unica diferencia entre UC08 y UC09 es el canal de captura en el frontend (Web Speech
 * API vs. {@code CommandPromptInput}), no el backend.
 *
 * <p>Regla de acceso: misma que {@code DiagramController#createDiagram} -- requiere rol
 * {@code OWNER} o {@code EDITOR} en el proyecto dueño del diagrama, porque este endpoint
 * puede mutar el modelo igual que una edicion manual.</p>
 */
@RestController
public class VoiceCommandController {

    private final DiagramRepository diagramRepository;
    private final ProjectRoleLookup projectRoleLookup;
    private final VoiceCommandParserService voiceCommandParserService;

    public VoiceCommandController(DiagramRepository diagramRepository,
                                   ProjectRoleLookup projectRoleLookup,
                                   VoiceCommandParserService voiceCommandParserService) {
        this.diagramRepository = diagramRepository;
        this.projectRoleLookup = projectRoleLookup;
        this.voiceCommandParserService = voiceCommandParserService;
    }

    @PostMapping("/api/v1/diagrams/{diagramId}/ai-command")
    public VoiceCommandResponse handleAiCommand(@PathVariable UUID diagramId,
                                                 @Valid @RequestBody VoiceCommandRequest request,
                                                 @AuthenticationPrincipal UUID userId) {
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));

        requireEditorOrOwner(diagram.getProjectId(), userId);

        return voiceCommandParserService.process(diagramId, request.command(), userId);
    }

    private void requireEditorOrOwner(UUID projectId, UUID userId) {
        String role = projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
        if (!"OWNER".equalsIgnoreCase(role) && !"EDITOR".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo OWNER o EDITOR pueden usar el modelado asistido por IA en este proyecto");
        }
    }
}
