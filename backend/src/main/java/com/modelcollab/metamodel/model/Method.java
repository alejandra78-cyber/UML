package com.modelcollab.metamodel.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Metodo de una clase del modelo canonico.
 */
public record Method(
        UUID id,
        String name,
        String returnType,
        Visibility visibility,
        List<Parameter> parameters
) {
    public Method {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(name, "name es obligatorio");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
