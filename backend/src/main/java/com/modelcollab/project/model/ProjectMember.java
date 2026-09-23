package com.modelcollab.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the {@code project_members} table, whose primary key
 * is the composite {@code (project_id, user_id)}.
 *
 * <p><b>Design choice: {@code @IdClass} over {@code @EmbeddedId}.</b> With
 * {@code @IdClass(ProjectMemberId.class)} the entity exposes {@code projectId}
 * and {@code userId} as plain top-level attributes, which lets Spring Data
 * derive {@code findByProjectIdAndUserId(...)} directly on
 * {@code ProjectMemberRepository} without nested-property path traversal
 * (e.g. {@code id.projectId}) that {@code @EmbeddedId} would require. This
 * matches the derived-query method name requested for this module.</p>
 */
@Entity
@Table(name = "project_members")
@IdClass(ProjectMemberId.class)
public class ProjectMember {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProjectRole role;

    protected ProjectMember() {
        // JPA
    }

    public ProjectMember(UUID projectId, UUID userId, ProjectRole role) {
        this.projectId = projectId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ProjectRole getRole() {
        return role;
    }

    public void setRole(ProjectRole role) {
        this.role = role;
    }
}
