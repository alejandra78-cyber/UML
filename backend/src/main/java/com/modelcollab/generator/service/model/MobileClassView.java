package com.modelcollab.generator.service.model;

/**
 * Vista mínima de una {@code ClassEntity} para las plantillas FreeMarker del
 * esqueleto móvil (UC14, ver {@code MobileAppGeneratorService}). A diferencia
 * de {@link ClassView} (usada por el generador de backend), esta vista no
 * carga ningún detalle JPA/relacional -- la app móvil solo necesita el nombre
 * de la entidad para mostrar la lista de comandos de voz disponibles.
 *
 * @param entityName         nombre PascalCase de la clase, p.ej. {@code "Cliente"}
 * @param resourcePathPlural nombre de recurso REST en plural minúsculas, p.ej.
 *                           {@code "clientes"} (mismo criterio de pluralización
 *                           simplificada que {@code ClassView}, ver {@code NameUtils#pluralize})
 */
public record MobileClassView(String entityName, String resourcePathPlural) {
}
