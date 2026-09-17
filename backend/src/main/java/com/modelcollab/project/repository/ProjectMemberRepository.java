package com.modelcollab.project.repository;

import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    List<ProjectMember> findByUserId(UUID userId);

    List<ProjectMember> findByProjectId(UUID projectId);

    /**
     * UC19 -- Eliminar Proyecto (borrado en cascada manual, ver
     * {@link com.modelcollab.diagram.repository.DiagramOperationRepository#deleteByDiagramId}
     * para el mismo razonamiento: no hay {@code ON DELETE CASCADE} en base de
     * datos).
     */
    long deleteByProjectId(UUID projectId);
}
