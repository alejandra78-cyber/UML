package com.modelcollab.project.repository;

import com.modelcollab.project.model.PendingInvitation;
import com.modelcollab.project.model.PendingInvitationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingInvitationRepository extends JpaRepository<PendingInvitation, PendingInvitationId> {

    Optional<PendingInvitation> findByProjectIdAndEmail(UUID projectId, String email);

    /** Usado por {@code AuthService.register} para vincular invitaciones pendientes al email recien registrado. */
    List<PendingInvitation> findByEmail(String email);

    /** UC19 -- Eliminar Proyecto (cascade manual, mismo criterio que {@code ProjectMemberRepository#deleteByProjectId}). */
    long deleteByProjectId(UUID projectId);
}
