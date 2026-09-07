package com.example.demo.project.controller;

import com.example.demo.project.dto.CreateProjectRequest;
import com.example.demo.project.dto.ProjectResponse;
import com.example.demo.project.model.Project;
import com.example.demo.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static UUID currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid authentication");
        }
        return userId;
    }
}
