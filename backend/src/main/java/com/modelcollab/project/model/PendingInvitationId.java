package com.modelcollab.project.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key class for {@link PendingInvitation}, used via
 * {@code @IdClass} (mismo patron que {@link ProjectMemberId} para
 * {@link ProjectMember}).
 *
 * <p>Field names ({@code projectId}, {@code email}) match the {@code @Id}
 * fields declared on {@link PendingInvitation} exactly, as required by
 * {@code @IdClass}.</p>
 */
public class PendingInvitationId implements Serializable {

    private UUID projectId;
    private String email;

    public PendingInvitationId() {
        // required by JPA
    }

    public PendingInvitationId(UUID projectId, String email) {
        this.projectId = projectId;
        this.email = email;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PendingInvitationId that)) {
            return false;
        }
        return Objects.equals(projectId, that.projectId) && Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, email);
    }
}
