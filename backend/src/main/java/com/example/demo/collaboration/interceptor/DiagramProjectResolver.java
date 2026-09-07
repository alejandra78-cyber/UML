package com.example.demo.collaboration.interceptor;

import java.util.Optional;
import java.util.UUID;

/**
 * Extension point analogous to {@link ProjectRoleLookup}: resolves which
 * project a diagram belongs to, so {@link StompChannelInterceptor} can turn
 * the {@code diagramId} present in the STOMP destination into the real
 * {@code projectId} required by {@link ProjectRoleLookup#findRole}.
 *
 * <p>Implemented by the {@code diagram} module against {@code diagrams.project_id}.</p>
 */
public interface DiagramProjectResolver {

    Optional<UUID> resolveProjectId(UUID diagramId);
}
