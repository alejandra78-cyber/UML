package com.modelcollab.project.controller;

import com.modelcollab.project.dto.CreateProjectRequest;
import com.modelcollab.project.dto.InviteMemberRequest;
import com.modelcollab.project.dto.ProjectResponse;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.service.ProjectService;
import com.modelcollab.project.service.ProjectService.MemberUpsertResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Section 14 endpoints: list the authenticated user's accessible projects,
 * and create a new project (creator becomes {@code OWNER}).
 *
 * <p>The authenticated user's id is read from the {@link Authentication}
 * principal, which {@code JwtAuthenticationFilter} populates with the
 * {@link UUID} decoded from the JWT {@code sub} claim.</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectResponse> listProjects(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        return projectService.listAccessibleProjects(userId).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(Authentication authentication,
                                                           @Valid @RequestBody CreateProjectRequest request) {
        UUID userId = currentUserId(authentication);
        Project project = projectService.createProject(userId, request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    /**
     * UC18 -- Invitar Colaborador y Asignar Rol. Only the project's
     * {@code OWNER} may invite (403 otherwise); the invitee is identified by
     * email and must already have an account (404 otherwise, via
     * {@link ProjectService#addOrUpdateMember}). If the invitee is already a
     * member, their role is updated in place (200 OK) instead of duplicating
     * the membership row (201 Created for a brand-new member).
     */
    @PostMapping("/{projectId}/members")
    public ResponseEntity<Void> addMember(Authentication authentication,
                                           @PathVariable UUID projectId,
                                           @Valid @RequestBody InviteMemberRequest request) {
        UUID requestingUserId = currentUserId(authentication);
        MemberUpsertResult result = projectService.addOrUpdateMember(projectId, requestingUserId,
                request.email(), request.role());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).build();
    }

    /**
     * UC19 -- Eliminar Proyecto. {@link ProjectService#deleteProject} enforces
     * 404 if the project doesn't exist and 403 if the caller isn't its
     * {@code OWNER}, then manually cascades the delete to
     * {@code diagram_operations}, {@code diagrams} and {@code project_members}
     * (no database-level {@code ON DELETE CASCADE} exists for these tables).
     */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(Authentication authentication, @PathVariable UUID projectId) {
        UUID requestingUserId = currentUserId(authentication);
        projectService.deleteProject(projectId, requestingUserId);
        return ResponseEntity.noContent().build();
    }

    private static UUID currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authentication");
        }
        return userId;
    }
}
