package com.modelcollab.xmi.controller;

import com.modelcollab.collaboration.interceptor.ProjectRoleLookup;
import com.modelcollab.diagram.dto.DiagramResponse;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.xmi.service.XmiExporterService;
import com.modelcollab.xmi.service.XmiImporterService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * UC15 -- Exportar Diagrama a XMI (PKG-06 -- Interoperabilidad XMI) y
 * UC16 -- Importar Diagrama desde XMI.
 *
 * <p>Reutiliza {@link ProjectRoleLookup} con el mismo criterio de autorizacion
 * que {@code DiagramController.getSnapshot}: cualquier miembro del proyecto
 * (cualquier rol, incluido {@code VIEWER}) puede exportar, ya que exportar no
 * muta el diagrama. Importar SI crea un diagrama nuevo, asi que sigue el mismo
 * criterio que {@code DiagramController.createDiagram}: requiere {@code OWNER}
 * o {@code EDITOR} en el proyecto.</p>
 *
 * <p><b>Content-Type:</b> se responde {@code application/xml} (no
 * {@code text/xml}) -- es el tipo MIME mas usado en APIs REST modernas para
 * XML de proposito general; {@code text/xml} arrastra semantica de "media tipo
 * texto" heredada (RFC 3023: sin parametro {@code charset} explicito, un
 * cliente estricto podria asumir US-ASCII), y esta respuesta ya declara UTF-8
 * de forma explicita en el cuerpo serializado, asi que esa distincion no
 * aplica aqui.</p>
 *
 * <p><b>Decision de diseno UC16 (endpoint a nivel de PROYECTO, no de diagrama):</b>
 * {@code PLAN_ARQUITECTONICO.md} seccion "Especificacion completa de endpoints
 * de la API REST" fija el path exacto como
 * {@code POST /api/v1/projects/{projectId}/import-xmi} -- importar XMI CREA un
 * diagrama nuevo dentro de un proyecto existente, por lo que naturalmente
 * cuelga de {@code {projectId}} y no de un {@code {diagramId}} que todavia no
 * existe. Se agrega el metodo a este {@code XmiController} existente (en vez
 * de crear un controller nuevo) porque ya es el dueno de toda la logica XMI
 * (UC15); el body se recibe como texto plano/XML crudo (no multipart) por
 * consistencia con como este mismo controller ya devuelve el export (bytes de
 * XML), evitando sumar una dependencia de multipart-file al proyecto solo para
 * este endpoint.</p>
 */
@RestController
public class XmiController {

    private final DiagramRepository diagramRepository;
    private final ProjectRoleLookup projectRoleLookup;
    private final ObjectMapper objectMapper;
    private final XmiExporterService xmiExporterService;
    private final XmiImporterService xmiImporterService;

    public XmiController(DiagramRepository diagramRepository, ProjectRoleLookup projectRoleLookup,
                          ObjectMapper objectMapper, XmiExporterService xmiExporterService,
                          XmiImporterService xmiImporterService) {
        this.diagramRepository = diagramRepository;
        this.projectRoleLookup = projectRoleLookup;
        this.objectMapper = objectMapper;
        this.xmiExporterService = xmiExporterService;
        this.xmiImporterService = xmiImporterService;
    }

    @GetMapping("/api/v1/diagrams/{diagramId}/export-xmi")
    public ResponseEntity<byte[]> exportXmi(@PathVariable UUID diagramId, @AuthenticationPrincipal UUID userId) {
        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diagram not found: " + diagramId));

        requireAnyMember(diagram.getProjectId(), userId);

        CanonicalModel model = objectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);
        String xmi = xmiExporterService.exportToXmi(model, diagram.getName());
        byte[] body = xmi.getBytes(StandardCharsets.UTF_8);

        String fileName = sanitizeFileName(diagram.getName()) + ".xmi";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    /**
     * UC16 -- Importar Diagrama desde XMI. Recibe el contenido XMI como texto
     * plano en el body (ver decision de diseno en el javadoc de la clase),
     * lo parsea con {@link XmiImporterService} y crea un {@link Diagram} nuevo
     * en el proyecto con el {@link CanonicalModel} resultante como
     * {@code current_state} -- mismo patron que {@code DiagramController.createDiagram},
     * salvo que el estado inicial viene del XMI parseado en vez de
     * {@link CanonicalModel#empty()}.
     *
     * @param diagramName nombre opcional para el diagrama nuevo; si se omite se
     *                     usa el {@code name} del {@code <uml:Model>} del XMI, o
     *                     "Diagrama importado" si tampoco ese está presente
     */
    @PostMapping("/api/v1/projects/{projectId}/import-xmi")
    public ResponseEntity<DiagramResponse> importXmi(@PathVariable UUID projectId,
                                                       @RequestParam(required = false) String diagramName,
                                                       @RequestBody String xmiContent,
                                                       @AuthenticationPrincipal UUID userId) {
        requireEditorOrOwner(projectId, userId);

        XmiImporterService.ImportResult importResult;
        try {
            importResult = xmiImporterService.importXmi(xmiContent);
        } catch (XmiImporterService.XmiImportException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El archivo XMI no se pudo importar: " + e.getMessage(), e);
        }
        String resolvedName = firstNonBlank(diagramName, importResult.diagramName(), "Diagrama importado");

        String currentState = objectMapper.writeValueAsString(importResult.model());
        Diagram diagram = new Diagram(projectId, resolvedName, importResult.model().schemaVersion(),
                importResult.model().mutationVersion(), currentState);
        diagram = diagramRepository.save(diagram);

        return ResponseEntity.status(HttpStatus.CREATED).body(DiagramResponse.from(diagram));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void requireAnyMember(UUID projectId, UUID userId) {
        projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
    }

    private void requireEditorOrOwner(UUID projectId, UUID userId) {
        String role = projectRoleLookup.findRole(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No eres miembro de este proyecto"));
        if (!"OWNER".equalsIgnoreCase(role) && !"EDITOR".equalsIgnoreCase(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo OWNER o EDITOR pueden importar un diagrama en este proyecto");
        }
    }

    private static String sanitizeFileName(String diagramName) {
        if (diagramName == null || diagramName.isBlank()) {
            return "diagrama";
        }
        return diagramName.replaceAll("[^a-zA-Z0-9-_ ]", "_").trim();
    }
}
