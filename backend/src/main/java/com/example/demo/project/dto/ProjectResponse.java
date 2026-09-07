package com.example.demo.project.dto;

import com.example.demo.project.model.Project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape for project listing/creation endpoints.
 */
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        OffsetDateTime createdAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getOwnerId(),
                project.getCreatedAt()
        );
    }
}
