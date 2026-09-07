package com.example.demo.diagram.repository;

import com.example.demo.diagram.model.Diagram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {

    @Query("select d.projectId from Diagram d where d.id = :id")
    Optional<UUID> findProjectIdById(@Param("id") UUID id);
}
