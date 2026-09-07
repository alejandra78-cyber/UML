package com.example.demo.project.service;

import com.example.demo.project.model.Project;
import com.example.demo.project.model.ProjectMember;
import com.example.demo.project.model.ProjectRole;
import com.example.demo.project.repository.ProjectMemberRepository;
import com.example.demo.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    /**
     * Creates a project and automatically makes the creator the {@code OWNER}
     * in {@code project_members}.
     */
    @Transactional
    public Project createProject(UUID ownerId, String name, String description) {
        Project project = projectRepository.save(new Project(name, description, ownerId));
        projectMemberRepository.save(new ProjectMember(project.getId(), ownerId, ProjectRole.OWNER));
        return project;
    }

    /**
     * Lists every project the given user can access: projects they own, plus
     * projects where they appear in {@code project_members} (any role).
     * Combines two simple derived queries in the service layer instead of a
     * single derived query navigating the {@code Project -> ProjectMember}
     * association, for portability across Spring Data JPA versions.
     */
    @Transactional(readOnly = true)
    public List<Project> listAccessibleProjects(UUID userId) {
        Map<UUID, Project> byId = new LinkedHashMap<>();

        for (Project owned : projectRepository.findByOwnerId(userId)) {
            byId.put(owned.getId(), owned);
        }

        List<UUID> memberProjectIds = projectMemberRepository.findByUserId(userId).stream()
                .map(ProjectMember::getProjectId)
                .toList();

        if (!memberProjectIds.isEmpty()) {
            for (Project project : projectRepository.findAllById(memberProjectIds)) {
                byId.putIfAbsent(project.getId(), project);
            }
        }

        return List.copyOf(byId.values());
    }
}
