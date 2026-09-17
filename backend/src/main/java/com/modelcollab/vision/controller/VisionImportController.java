package com.modelcollab.vision.controller;

import com.modelcollab.collaboration.interceptor.ProjectRoleLookup;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.vision.dto.DraftModelResponse;
import com.modelcollab.vision.dto.VisionImportRequest;
import com.modelcollab.vision.service.VisionOcrService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

/**
 * UC10 -- Importar Diagrama desde Foto de Pizarra (PKG-03 -- Modelado Asistido
 * por IA). Sigue el mismo patron de autorizacion duplicado a proposito que
 * {@code DiagramController}/{@code XmiController}/{@code GeneratorController}
 * (todavia no hay una clase base comun en este codebase): analizar una imagen
 * y proponer cambios es una accion de edicion, asi que requiere el mismo
 * criterio que crear un diagrama -- {@code OWNER} o {@code EDITOR} en el
 * proyecto.
 *
 * <p><b>Importante -- este endpoint NUNCA aplica ninguna mutacion real sobre el
 * diagrama persistido.</b> Solo lee el {@link Diagram} para resolver el rol y
 * para darle a {@link VisionOcrService} el {@link CanonicalModel} actual como
 * contexto; jamas llama a {@code diagramRepository.save(...)} ni a
 * {@code CanonicalModelMutator}. Devuelve un {@link DraftModelResponse} para
 * que el frontend lo revise en un modal Human-in-the-Loop (RF-03.3) y, si el
 * usuario confirma, dispare el {@code BULK_MERGE} ya existente por el canal
 * STOMP de colaboracion -- ver el javadoc de {@code package-info.java} de este
 * paquete y de {@link DraftModelResponse#toBulkMergePayload()}.</p>
 */
@RestController
public class VisionImportController {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final DiagramRepository diagramRepository;
    private final ProjectRoleLookup projectRoleLookup;
    private final ObjectMapper objectMapper;
    private final VisionOcrService visionOcrService;

    public VisionImportController(DiagramRepository diagramRepository, ProjectRoleLookup projectRoleLookup,
                                   ObjectMapper objectMapper, VisionOcrService visionOcrService) {
        this.diagramRepository = diagramRepository;
        this.projectRoleLookup = projectRoleLookup;
        this.objectMapper = objectMapper;
        this.visionOcrService = visionOcrService;
    }

    @PostMapping("/api/v1/diagrams/{diagramId}/vision-import")
    public DraftModelResponse visionImport(@PathVariable UUID diagramId,
                                            @RequestPart("image") MultipartFile image,
                                            @AuthenticationPrincipal UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        requireEditorOrOwner(diagram.getProjectId(), userId);

        String mimeType = validateImage(image);
        CanonicalModel currentModel = objectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la imagen enviada", e);
        }

        VisionImportRequest request = new VisionImportRequest(imageBytes, mimeType, currentModel);
        // Deliberadamente NO hay ningun diagramRepository.save(...) ni llamada a
        // CanonicalModelMutator en este metodo: ver el javadoc de la clase.
        return visionOcrService.analyzeSketch(diagramId, request);
    }

    private String validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La imagen es obligatoria");
        }
        String contentType = image.getContentType();
        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de imagen no soportado: " + contentType + " (se espera image/jpeg o image/png)");
        }
        return contentType.toLowerCase();
    }

    private Diagram findDiagram(UUID diagramId) {
        return diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));
    }

    private void requireEditorOrOwner(UUID projectId, UUID userId) {
        String role = projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
        if (!"OWNER".equalsIgnoreCase(role) && !"EDITOR".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo OWNER o EDITOR pueden importar una foto de pizarra en este proyecto");
        }
    }
}
