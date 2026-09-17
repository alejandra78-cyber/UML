package com.modelcollab.diagram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to create a diagram within a project (future
 * {@code POST /api/v1/projects/{projectId}/diagrams} or similar -- the
 * controller/service for this is out of scope for this task).
 */
public record DiagramRequest(

        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must be at most 150 characters")
        String name
) {
}
