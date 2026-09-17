package com.modelcollab.metamodel.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Clase (entidad) del modelo canonico UML/ER.
 */
public record ClassEntity(
        UUID id,
        String name,
        Visibility visibility,
        boolean isAbstract,
        Position position,
        double width,
        double height,
        List<Attribute> attributes,
        List<Method> methods
) {
    public ClassEntity {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(name, "name es obligatorio");
        Objects.requireNonNull(position, "position es obligatorio");
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String name;
        private Visibility visibility = Visibility.PUBLIC;
        private boolean isAbstract = false;
        private Position position = new Position(0, 0);
        private double width = 240;
        private double height = 180;
        private List<Attribute> attributes = List.of();
        private List<Method> methods = List.of();

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder visibility(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder isAbstract(boolean isAbstract) {
            this.isAbstract = isAbstract;
            return this;
        }

        public Builder position(Position position) {
            this.position = position;
            return this;
        }

        public Builder width(double width) {
            this.width = width;
            return this;
        }

        public Builder height(double height) {
            this.height = height;
            return this;
        }

        public Builder attributes(List<Attribute> attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder methods(List<Method> methods) {
            this.methods = methods;
            return this;
        }

        public ClassEntity build() {
            return new ClassEntity(id, name, visibility, isAbstract, position, width, height, attributes, methods);
        }
    }
}
