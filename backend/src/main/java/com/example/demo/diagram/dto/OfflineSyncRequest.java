package com.example.demo.diagram.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Payload for the future {@code POST /api/v1/diagrams/{id}/sync-offline}
 * endpoint: a batch of operations produced while the client was offline,
 * to be reconciled/replayed against the server's current sequence.
 */
public record OfflineSyncRequest(

        @NotEmpty(message = "operations must not be empty")
        List<@Valid PendingOperation> operations
) {

    /**
     * One client-authored, not-yet-applied operation.
     */
    public record PendingOperation(

            @NotBlank(message = "clientMutationId is required")
            String clientMutationId,

            @NotBlank(message = "operationType is required")
            String operationType,

            @NotNull(message = "payload is required")
            String payload,

            @NotNull(message = "clientTimestamp is required")
            OffsetDateTime clientTimestamp
    ) {
    }
}
