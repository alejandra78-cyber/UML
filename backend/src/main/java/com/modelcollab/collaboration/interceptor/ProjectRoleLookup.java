package com.modelcollab.collaboration.interceptor;

import java.util.Optional;
import java.util.UUID;

/**
 * Punto de extension para consultar el rol de un usuario dentro de un proyecto
 * (tabla {@code project_members.role}). Todavia NO existe una implementacion:
 * el repositorio de {@code ProjectMember} lo crea otro agente en paralelo.
 *
 * <p>{@link StompChannelInterceptor} depende de esta interfaz como un bean
 * {@code Optional} (no obligatorio) precisamente para no bloquear el arranque de
 * la aplicacion mientras no exista ninguna implementacion registrada: cuando el
 * otro modulo aporte un {@code @Component} que implemente {@link #findRole},
 * Spring lo inyectara automaticamente sin cambios en este interceptor.</p>
 */
public interface ProjectRoleLookup {

    /**
     * @param projectId id del proyecto
     * @param userId    id del usuario
     * @return el rol del usuario en ese proyecto (p.ej. "OWNER", "EDITOR", "VIEWER"),
     *         o {@link Optional#empty()} si el usuario no es miembro o no se pudo resolver
     */
    Optional<String> findRole(UUID projectId, UUID userId);
}
