package com.modelcollab.metamodel.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Atributo (columna) de una clase del modelo canonico.
 * Se ofrece un {@link Builder} porque la mayoria de los campos numericos/booleanos
 * tienen un valor por defecto segun el esquema JSON canonico (seccion 7 del documento
 * de arquitectura) y un record con constructor canonico obligaria a repetir esos
 * defaults en cada punto de construccion.
 */
public record Attribute(
        UUID id,
        String name,
        AttributeType type,
        int length,
        int precision,
        int scale,
        Visibility visibility,
        boolean isPrimaryKey,
        boolean isNullable,
        boolean isUnique,
        String defaultValue
) {
    public Attribute {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(name, "name es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(visibility, "visibility es obligatorio");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String name;
        private AttributeType type;
        private int length = 255;
        private int precision = 10;
        private int scale = 2;
        private Visibility visibility;
        private boolean isPrimaryKey = false;
        private boolean isNullable = true;
        private boolean isUnique = false;
        private String defaultValue;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(AttributeType type) {
            this.type = type;
            return this;
        }

        public Builder length(int length) {
            this.length = length;
            return this;
        }

        public Builder precision(int precision) {
            this.precision = precision;
            return this;
        }

        public Builder scale(int scale) {
            this.scale = scale;
            return this;
        }

        public Builder visibility(Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder primaryKey(boolean primaryKey) {
            this.isPrimaryKey = primaryKey;
            return this;
        }

        public Builder nullable(boolean nullable) {
            this.isNullable = nullable;
            return this;
        }

        public Builder unique(boolean unique) {
            this.isUnique = unique;
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Attribute build() {
            return new Attribute(id, name, type, length, precision, scale, visibility,
                    isPrimaryKey, isNullable, isUnique, defaultValue);
        }
    }
}
