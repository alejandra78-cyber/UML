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
 * Invitacion a un proyecto para un email que todavia NO tiene cuenta registrada
 * (UC19 -- Invitar Colaborador). Tabla nueva y aislada, deliberadamente separada
 * de {@link ProjectMember}: {@code ProjectMember} tiene PK compuesta
 * {@code (project_id, user_id)} con {@code user_id} obligatorio (no admite
 * {@code null}), asi que no hay forma de registrar ahi una invitacion sin
 * usuario todavia sin cambiar esa PK -- una tabla nueva es el cambio de schema
 * mas chico y no toca la entidad {@code ProjectMember} ya probada.
 *
 * <p>{@link com.modelcollab.auth.service.AuthService#register} vincula
 * automaticamente estas filas al {@code User} recien creado (por email) y las
 * borra, convirtiendolas en un {@link ProjectMember} real -- ver su javadoc.
 * Deliberadamente NO hay flujo de aceptar/rechazar invitacion (fuera de
 * alcance): registrarse con el email invitado alcanza para quedar sumado.</p>
 */
@Entity
@Table(name = "pending_invitations")
@IdClass(PendingInvitationId.class)
public class PendingInvitation {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Id
    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProjectRole role;

    @CreationTimestamp
    @Column(name = "invited_at")
    private OffsetDateTime invitedAt;

    protected PendingInvitation() {
        // JPA
    }

    public PendingInvitation(UUID projectId, String email, ProjectRole role) {
        this.projectId = projectId;
        this.email = email;
        this.role = role;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getEmail() {
        return email;
    }

    public ProjectRole getRole() {
        return role;
    }

    public void setRole(ProjectRole role) {
        this.role = role;
    }

    public OffsetDateTime getInvitedAt() {
        return invitedAt;
    }
}
