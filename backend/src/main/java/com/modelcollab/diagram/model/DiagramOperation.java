package com.modelcollab.diagram.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the {@code diagram_operations} table (the operation
 * log used for the reconciliation/replay flow described in section 9.2).
 *
 * <p>{@code payload} uses the same JSON mapping strategy as
 * {@code Diagram.currentState} -- see the javadoc on {@link Diagram} for the
 * rationale (raw JSON string, no extra dependency, avoids coupling to
 * {@code metamodel.model.CanonicalModel} while that module is still being
 * built in parallel).</p>
 *
 * <p>{@code user_id} is nullable to mirror the {@code ON DELETE SET NULL}
 * behavior on the {@code users} FK (an operation must be retained for audit
 * history even if the authoring user is later deleted).</p>
 */
@Entity
@Table(name = "diagram_operations")
public class DiagramOperation {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "client_mutation_id", length = 100)
    private String clientMutationId;

    @Column(name = "sequence_num", nullable = false)
    private long sequenceNum;

    @Column(name = "server_timestamp")
    private OffsetDateTime serverTimestamp;

    protected DiagramOperation() {
        // JPA
    }

    public DiagramOperation(UUID diagramId, UUID userId, String operationType, String payload,
                             String clientMutationId, long sequenceNum) {
        this.diagramId = diagramId;
        this.userId = userId;
        this.operationType = operationType;
        this.payload = payload;
        this.clientMutationId = clientMutationId;
        this.sequenceNum = sequenceNum;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiagramId() {
        return diagramId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getPayload() {
        return payload;
    }

    public String getClientMutationId() {
        return clientMutationId;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public OffsetDateTime getServerTimestamp() {
        return serverTimestamp;
    }
}
