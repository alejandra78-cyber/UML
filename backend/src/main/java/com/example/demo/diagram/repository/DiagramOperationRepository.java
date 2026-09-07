package com.example.demo.diagram.repository;

import com.example.demo.diagram.model.DiagramOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiagramOperationRepository extends JpaRepository<DiagramOperation, UUID> {
}
