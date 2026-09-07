package com.example.demo.project.service;

import com.example.demo.collaboration.interceptor.ProjectRoleLookup;
import com.example.demo.project.repository.ProjectMemberRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Wires the {@code project_members} table into
 * {@link com.example.demo.collaboration.interceptor.ProjectRoleLookup}, the
 * extension point defined by the collaboration module (created by a parallel
 * agent) so that {@code StompChannelInterceptor} can resolve a user's role
 * within a project without a hard dependency between the two modules.
 */
@Component
public class ProjectRoleLookupImpl implements ProjectRoleLookup {

    private final ProjectMemberRepository projectMemberRepository;

    public ProjectRoleLookupImpl(ProjectMemberRepository projectMemberRepository) {
        this.projectMemberRepository = projectMemberRepository;
    }

    @Override
    public Optional<String> findRole(UUID projectId, UUID userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole().name());
    }
}
