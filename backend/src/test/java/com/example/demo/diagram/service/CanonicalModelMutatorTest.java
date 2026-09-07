package com.example.demo.diagram.service;

import com.example.demo.collaboration.dto.OperationType;
import com.example.demo.metamodel.model.Attribute;
import com.example.demo.metamodel.model.AttributeType;
import com.example.demo.metamodel.model.CanonicalModel;
import com.example.demo.metamodel.model.ClassEntity;
import com.example.demo.metamodel.model.OwningSide;
import com.example.demo.metamodel.model.Position;
import com.example.demo.metamodel.model.Relationship;
import com.example.demo.metamodel.model.RelationshipType;
import com.example.demo.metamodel.model.Visibility;
import com.example.demo.metamodel.model.Waypoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No forma parte de la lista explicita de la seccion 4.1, pero es diligencia
 * basica antes de dar por cerrado un motor de mutacion nuevo que depende de
 * deserializacion Jackson 3 (record + enums) recien verificada en este mismo
 * cambio: cubre un representante de cada familia de operacion (ADD/MOVE/
 * UPDATE-parcial/DELETE-con-cascada) mas el caso explicito del documento de
 * "no destruir y recrear el conector" en UPDATE_RELATIONSHIP.
 */
class CanonicalModelMutatorTest {

    private final CanonicalModelMutator mutator = new CanonicalModelMutator(new ObjectMapper());

    @Test
    void addClass_insertsNewClassFromFullPayload() {
        CanonicalModel model = CanonicalModel.empty();
        UUID classId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "id", classId.toString(),
                "name", "Cliente",
                "visibility", "PUBLIC",
                "isAbstract", false,
                "position", Map.of("x", 10.0, "y", 20.0),
                "width", 240.0,
                "height", 180.0,
                "attributes", List.of(),
                "methods", List.of());

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(model, OperationType.ADD_CLASS, null, payload);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.model().classes()).hasSize(1);
        assertThat(outcome.model().classes().get(0).id()).isEqualTo(classId);
        assertThat(outcome.model().classes().get(0).name()).isEqualTo("Cliente");
    }

    @Test
    void addClass_rejectsDuplicateId() {
        UUID classId = UUID.randomUUID();
        ClassEntity existing = classEntity(classId, "Cliente", 0, 0);
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(existing), List.of());
        Map<String, Object> payload = Map.of(
                "id", classId.toString(), "name", "Otro", "visibility", "PUBLIC", "isAbstract", false,
                "position", Map.of("x", 0.0, "y", 0.0),
                "width", 240.0, "height", 180.0, "attributes", List.of(), "methods", List.of());

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(model, OperationType.ADD_CLASS, null, payload);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).contains(classId.toString());
    }

    @Test
    void moveClass_updatesPositionOnly_preservingOtherFields() {
        UUID classId = UUID.randomUUID();
        ClassEntity existing = classEntity(classId, "Cliente", 0, 0);
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(existing), List.of());

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.MOVE_CLASS, classId, Map.of("x", 340.0, "y", 260.0));

        assertThat(outcome.applied()).isTrue();
        ClassEntity moved = outcome.model().findClass(classId).orElseThrow();
        assertThat(moved.position()).isEqualTo(new Position(340.0, 260.0));
        assertThat(moved.name()).isEqualTo("Cliente");
    }

    @Test
    void moveClass_rejectsWhenClassNotFound() {
        CanonicalModel model = CanonicalModel.empty();

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.MOVE_CLASS, UUID.randomUUID(), Map.of("x", 1.0, "y", 1.0));

        assertThat(outcome.applied()).isFalse();
    }

    @Test
    void updateRelationship_mergesOnlyGivenFields_preservingIdSourceTargetAndWaypoints() {
        UUID relId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        List<Waypoint> originalWaypoints = List.of(new Waypoint(5, 5));
        Relationship existing = new Relationship(relId, sourceId, targetId, RelationshipType.ASSOCIATION,
                OwningSide.SOURCE, null, null, null, "cliente", "factura", true, originalWaypoints);
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(), List.of(existing));

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.UPDATE_RELATIONSHIP, relId, Map.of("type", "AGGREGATION"));

        assertThat(outcome.applied()).isTrue();
        Relationship updated = outcome.model().relationships().get(0);
        assertThat(updated.id()).isEqualTo(relId);
        assertThat(updated.sourceClassId()).isEqualTo(sourceId);
        assertThat(updated.targetClassId()).isEqualTo(targetId);
        assertThat(updated.waypoints()).isEqualTo(originalWaypoints);
        assertThat(updated.type()).isEqualTo(RelationshipType.AGGREGATION);
        assertThat(updated.sourceRole()).isEqualTo("cliente");
    }

    @Test
    void deleteClass_cascadesToOrphanedRelationships() {
        UUID classId = UUID.randomUUID();
        UUID otherClassId = UUID.randomUUID();
        ClassEntity toDelete = classEntity(classId, "Cliente", 0, 0);
        ClassEntity other = classEntity(otherClassId, "Factura", 300, 0);
        Relationship relationship = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(classId).targetClassId(otherClassId).type(RelationshipType.ASSOCIATION).build();
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(toDelete, other), List.of(relationship));

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(model, OperationType.DELETE_CLASS, classId, Map.of());

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.model().classes()).extracting(ClassEntity::id).containsExactly(otherClassId);
        assertThat(outcome.model().relationships()).isEmpty();
    }

    @Test
    void addAttribute_rejectsWhenTargetClassMissing() {
        CanonicalModel model = CanonicalModel.empty();
        Map<String, Object> payload = Map.of(
                "id", UUID.randomUUID().toString(), "name", "nombre", "type", "VARCHAR", "visibility", "PRIVATE");

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.ADD_ATTRIBUTE, UUID.randomUUID(), payload);

        assertThat(outcome.applied()).isFalse();
    }

    private static ClassEntity classEntity(UUID id, String name, double x, double y) {
        return ClassEntity.builder().id(id).name(name).position(new Position(x, y))
                .attributes(List.of(Attribute.builder().id(UUID.randomUUID()).name("id")
                        .type(AttributeType.UUID).visibility(Visibility.PRIVATE).primaryKey(true).build()))
                .build();
    }
}
