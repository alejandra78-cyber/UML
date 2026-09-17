package com.modelcollab.generator.controller;

import com.modelcollab.collaboration.interceptor.ProjectRoleLookup;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.generator.service.MobileAppGeneratorService;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.generator.service.ZipPackagingService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.metamodel.service.ValidationResult;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * UC13 -- Generar Backend Spring Boot (PKG-05 -- Generación de Artefactos).
 *
 * <p>Sigue exactamente el mismo criterio de autorización que
 * {@code DiagramController}/{@code XmiController}, reutilizando
 * {@link ProjectRoleLookup}: leer/validar solo requiere ser miembro del proyecto
 * (cualquier rol); generar y descargar el backend requiere {@code OWNER} o
 * {@code EDITOR}, igual que crear un diagrama.</p>
 *
 * <p>Deliberadamente NO se toca {@code DiagramController} (propiedad de otro
 * agente en esta oleada): este controlador vive en su propio paquete
 * {@code generator.controller} y resuelve el diagrama/rol con el mismo patrón
 * duplicado a propósito (no hay una clase base común todavía).</p>
 */
@RestController
public class GeneratorController {

    private final DiagramRepository diagramRepository;
    private final ProjectRoleLookup projectRoleLookup;
    private final ObjectMapper objectMapper;
    private final MetamodelValidator metamodelValidator;
    private final SpringBootGeneratorService springBootGeneratorService;
    private final MobileAppGeneratorService mobileAppGeneratorService;
    private final ZipPackagingService zipPackagingService;

    public GeneratorController(DiagramRepository diagramRepository, ProjectRoleLookup projectRoleLookup,
                                ObjectMapper objectMapper, MetamodelValidator metamodelValidator,
                                SpringBootGeneratorService springBootGeneratorService,
                                MobileAppGeneratorService mobileAppGeneratorService,
                                ZipPackagingService zipPackagingService) {
        this.diagramRepository = diagramRepository;
        this.projectRoleLookup = projectRoleLookup;
        this.objectMapper = objectMapper;
        this.metamodelValidator = metamodelValidator;
        this.springBootGeneratorService = springBootGeneratorService;
        this.mobileAppGeneratorService = mobileAppGeneratorService;
        this.zipPackagingService = zipPackagingService;
    }

    /**
     * RF-06.7 / sección 13.2 punto 6: reporte de pre-generación. Cualquier miembro
     * del proyecto puede consultarlo (es de solo lectura, igual que el snapshot y el
     * export XMI).
     */
    @GetMapping("/api/v1/diagrams/{diagramId}/validate")
    public ValidationResult validate(@PathVariable UUID diagramId, @AuthenticationPrincipal UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        requireAnyMember(diagram.getProjectId(), userId);

        CanonicalModel model = objectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);
        return metamodelValidator.validate(model);
    }

    /**
     * Genera y descarga el backend Spring Boot generado a partir del diagrama.
     * Corre la validación primero: si hay algún {@link com.modelcollab.metamodel.service.ValidationIssue}
     * con severidad ERROR, rechaza con 422 y el detalle de errores, SIN generar nada
     * (RF-06.7: "impidiendo descargas defectuosas").
     *
     * @param basePackage paquete base opcional del proyecto generado (p.ej.
     *                    {@code com.empresa.app}); si se omite se deriva de un valor
     *                    por defecto en {@code SpringBootGeneratorService}
     */
    @PostMapping("/api/v1/diagrams/{diagramId}/generate-backend")
    public ResponseEntity<?> generateBackend(@PathVariable UUID diagramId,
                                              @RequestParam(required = false) String basePackage,
                                              @AuthenticationPrincipal UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        requireEditorOrOwner(diagram.getProjectId(), userId);

        CanonicalModel model = objectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);
        ValidationResult validationResult = metamodelValidator.validate(model);
        if (!validationResult.valid()) {
            return ResponseEntity.unprocessableEntity().body(validationResult);
        }

        Path projectRoot = null;
        try {
            projectRoot = springBootGeneratorService.generate(model, basePackage, diagram.getName());
            byte[] zipBytes = zipPackagingService.zipDirectory(projectRoot);
            String fileName = sanitizeFileName(diagram.getName()) + "-backend.zip";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .body(zipBytes);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el backend para el diagrama " + diagramId, e);
        } finally {
            if (projectRoot != null) {
                zipPackagingService.deleteRecursively(projectRoot);
            }
        }
    }

    /**
     * UC14 -- Generar Aplicación Móvil (RF-07 / sección 12 del plan
     * arquitectónico). Mismo criterio de autorización y de validación previa
     * que {@link #generateBackend}: corre {@link MetamodelValidator} primero y
     * rechaza con 422 si hay errores bloqueantes, sin generar nada.
     *
     * <p>El path exacto ({@code /api/v1/diagrams/{diagramId}/generate-mobile})
     * es el documentado en {@code PLAN_ARQUITECTONICO.md}, sección "Especificación
     * completa de endpoints de la API REST".</p>
     */
    @PostMapping("/api/v1/diagrams/{diagramId}/generate-mobile")
    public ResponseEntity<?> generateMobile(@PathVariable UUID diagramId, @AuthenticationPrincipal UUID userId) {
        Diagram diagram = findDiagram(diagramId);
        requireEditorOrOwner(diagram.getProjectId(), userId);

        CanonicalModel model = objectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);
        ValidationResult validationResult = metamodelValidator.validate(model);
        if (!validationResult.valid()) {
            return ResponseEntity.unprocessableEntity().body(validationResult);
        }

        Path projectRoot = null;
        try {
            projectRoot = mobileAppGeneratorService.generate(model, diagram.getName());
            byte[] zipBytes = zipPackagingService.zipDirectory(projectRoot);
            String fileName = sanitizeFileName(diagram.getName()) + "-mobile.zip";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .body(zipBytes);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar la app móvil para el diagrama " + diagramId, e);
        } finally {
            if (projectRoot != null) {
                zipPackagingService.deleteRecursively(projectRoot);
            }
        }
    }

    private Diagram findDiagram(UUID diagramId) {
        return diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));
    }

    private void requireAnyMember(UUID projectId, UUID userId) {
        requireMemberRole(projectId, userId);
    }

    private void requireEditorOrOwner(UUID projectId, UUID userId) {
        String role = requireMemberRole(projectId, userId);
        if (!"OWNER".equalsIgnoreCase(role) && !"EDITOR".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo OWNER o EDITOR pueden generar el backend de este proyecto");
        }
    }

    private String requireMemberRole(UUID projectId, UUID userId) {
        return projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
    }

    private static String sanitizeFileName(String diagramName) {
        if (diagramName == null || diagramName.isBlank()) {
            return "diagrama";
        }
        return diagramName.replaceAll("[^a-zA-Z0-9-_ ]", "_").trim();
    }
}
