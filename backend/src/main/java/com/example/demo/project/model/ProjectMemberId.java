package com.example.demo.project.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key class for {@link ProjectMember}, used via
 * {@code @IdClass} (see {@link ProjectMember}).
 *
 * <p>Field names ({@code projectId}, {@code userId}) match the {@code @Id}
 * fields declared on {@link ProjectMember} exactly, as required by
 * {@code @IdClass}.</p>
 */
public class ProjectMemberId implements Serializable {

    private UUID projectId;
    private UUID userId;

    public ProjectMemberId() {
        // required by JPA
    }

    public ProjectMemberId(UUID projectId, UUID userId) {
        this.projectId = projectId;
        this.userId = userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectMemberId that)) {
            return false;
        }
        return Objects.equals(projectId, that.projectId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, userId);
    }
}
