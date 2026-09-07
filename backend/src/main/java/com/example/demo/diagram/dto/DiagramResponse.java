package com.example.demo.diagram.dto;

import com.example.demo.diagram.model.Diagram;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Diagram metadata only -- deliberately excludes the full {@code current_state}
 * JSONB payload (see {@link SnapshotResponse} for that).
 */
public record DiagramResponse(
        UUID id,
        UUID projectId,
        String name,
        String schemaVersion,
        long mutationVersion,
        OffsetDateTime updatedAt
) {
    public static DiagramResponse from(Diagram diagram) {
        return new DiagramResponse(
                diagram.getId(),
                diagram.getProjectId(),
                diagram.getName(),
                diagram.getSchemaVersion(),
                diagram.getMutationVersion(),
                diagram.getUpdatedAt()
        );
    }
}
