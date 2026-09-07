package com.example.demo.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/projects}.
 */
public record CreateProjectRequest(

        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must be at most 150 characters")
        String name,

        String description
) {
}
