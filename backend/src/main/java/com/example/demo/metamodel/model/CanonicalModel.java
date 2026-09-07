package com.example.demo.metamodel.model;

import java.util.List;
import java.util.UUID;

/**
 * Raiz del grafo canonico de un diagrama (seccion 7 del documento de arquitectura).
 * Esta clase es el modelo de dominio en memoria usado por el modulo de colaboracion
 * y el validador; NO es una entidad JPA. La persistencia real vive en la columna
 * JSONB de {@code diagram.model.Diagram} (modulo de otro agente), que serializa/
 * deserializa este mismo esquema.
 */
public record CanonicalModel(
        String schemaVersion,
        int mutationVersion,
        List<Package> packages,
        List<ClassEntity> classes,
        List<Relationship> relationships
) {
    public CanonicalModel {
        schemaVersion = (schemaVersion == null || schemaVersion.isBlank()) ? "1.0.0" : schemaVersion;
        if (mutationVersion < 1) {
            mutationVersion = 1;
        }
        packages = packages == null ? List.of() : List.copyOf(packages);
        classes = classes == null ? List.of() : List.copyOf(classes);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }

    public static CanonicalModel empty() {
        return new CanonicalModel("1.0.0", 1, List.of(), List.of(), List.of());
    }

    public java.util.Optional<ClassEntity> findClass(UUID classId) {
        return classes.stream().filter(c -> c.id().equals(classId)).findFirst();
    }
}
