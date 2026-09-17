package com.modelcollab.metamodel.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Relacion estructural entre dos clases del modelo canonico.
 */
public record Relationship(
        UUID id,
        UUID sourceClassId,
        UUID targetClassId,
        RelationshipType type,
        OwningSide owningSide,
        String joinTableName,
        Multiplicity sourceMultiplicity,
        Multiplicity targetMultiplicity,
        String sourceRole,
        String targetRole,
        boolean isNavigable,
        List<Waypoint> waypoints
) {
    public Relationship {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(sourceClassId, "sourceClassId es obligatorio");
        Objects.requireNonNull(targetClassId, "targetClassId es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID sourceClassId;
        private UUID targetClassId;
        private RelationshipType type;
        private OwningSide owningSide = OwningSide.SOURCE;
        private String joinTableName;
        private Multiplicity sourceMultiplicity;
        private Multiplicity targetMultiplicity;
        private String sourceRole;
        private String targetRole;
        private boolean isNavigable = true;
        private List<Waypoint> waypoints = List.of();

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder sourceClassId(UUID sourceClassId) {
            this.sourceClassId = sourceClassId;
            return this;
        }

        public Builder targetClassId(UUID targetClassId) {
            this.targetClassId = targetClassId;
            return this;
        }

        public Builder type(RelationshipType type) {
            this.type = type;
            return this;
        }

        public Builder owningSide(OwningSide owningSide) {
            this.owningSide = owningSide;
            return this;
        }

        public Builder joinTableName(String joinTableName) {
            this.joinTableName = joinTableName;
            return this;
        }

        public Builder sourceMultiplicity(Multiplicity sourceMultiplicity) {
            this.sourceMultiplicity = sourceMultiplicity;
            return this;
        }

        public Builder targetMultiplicity(Multiplicity targetMultiplicity) {
            this.targetMultiplicity = targetMultiplicity;
            return this;
        }

        public Builder sourceRole(String sourceRole) {
            this.sourceRole = sourceRole;
            return this;
        }

        public Builder targetRole(String targetRole) {
            this.targetRole = targetRole;
            return this;
        }

        public Builder isNavigable(boolean isNavigable) {
            this.isNavigable = isNavigable;
            return this;
        }

        public Builder waypoints(List<Waypoint> waypoints) {
            this.waypoints = waypoints;
            return this;
        }

        public Relationship build() {
            return new Relationship(id, sourceClassId, targetClassId, type, owningSide, joinTableName,
                    sourceMultiplicity, targetMultiplicity, sourceRole, targetRole, isNavigable, waypoints);
        }
    }
}
