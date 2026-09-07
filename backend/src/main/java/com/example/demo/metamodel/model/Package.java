package com.example.demo.metamodel.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Paquete logico contenedor de clases dentro del modelo canonico.
 */
public record Package(UUID id, String name, List<UUID> classIds) {
    public Package {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(name, "name es obligatorio");
        classIds = classIds == null ? List.of() : List.copyOf(classIds);
    }
}
