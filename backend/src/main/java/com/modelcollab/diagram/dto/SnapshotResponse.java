package com.modelcollab.diagram.dto;

import java.util.UUID;

/**
 * Full canonical state snapshot for {@code GET /api/v1/diagrams/{id}/snapshot}
 * (endpoint to be implemented in a later phase).
 *
 * <p>{@code currentState} is the raw JSON text of {@code Diagram.currentState}
 * (see that entity's javadoc for why JSONB is modeled as {@code String}
 * rather than a typed object at this stage).</p>
 */
public record SnapshotResponse(
        UUID id,
        String currentState
) {
}
