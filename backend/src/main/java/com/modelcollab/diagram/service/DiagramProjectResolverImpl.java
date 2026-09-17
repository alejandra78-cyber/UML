package com.modelcollab.diagram.service;

import com.modelcollab.collaboration.interceptor.DiagramProjectResolver;
import com.modelcollab.diagram.repository.DiagramRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Wires {@code diagrams.project_id} into
 * {@link com.modelcollab.collaboration.interceptor.DiagramProjectResolver}, so
 * {@code StompChannelInterceptor} can resolve the real project a diagram
 * belongs to instead of treating the diagramId as a projectId substitute.
 */
@Component
public class DiagramProjectResolverImpl implements DiagramProjectResolver {

    private final DiagramRepository diagramRepository;

    public DiagramProjectResolverImpl(DiagramRepository diagramRepository) {
        this.diagramRepository = diagramRepository;
    }

    @Override
    public Optional<UUID> resolveProjectId(UUID diagramId) {
        return diagramRepository.findProjectIdById(diagramId);
    }
}
