package com.example.demo.project.repository;

import com.example.demo.project.model.ProjectMember;
import com.example.demo.project.model.ProjectMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    List<ProjectMember> findByUserId(UUID userId);

    List<ProjectMember> findByProjectId(UUID projectId);
}
