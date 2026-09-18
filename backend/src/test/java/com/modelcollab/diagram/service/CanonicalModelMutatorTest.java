package com.modelcollab.diagram.service;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.model.Waypoint;
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
    void addClass_fillsSchemaDefaultsWhenGeminiOmitsWidthAndHeight() {
        // Reproduce el bug real encontrado con Gemini real (no stub): al pedir
        // "Crea la clase Factura con atributo total decimal", la respuesta de
        // ADD_CLASS vino sin "width"/"height" (null), y como ClassEntity.width()/
        // height() son primitivos, Jackson rechazaba la operacion entera con
        // "Cannot map null into type double" antes de este fix.
        CanonicalModel model = CanonicalModel.empty();
        UUID classId = UUID.randomUUID();
        Map<String, Object> payloadWithNulls = new java.util.HashMap<>();
        payloadWithNulls.put("id", classId.toString());
        payloadWithNulls.put("name", "Factura");
        payloadWithNulls.put("visibility", "PUBLIC");
        payloadWithNulls.put("position", Map.of("x", 0.0, "y", 0.0));
        payloadWithNulls.put("width", null);
        payloadWithNulls.put("height", null);
        payloadWithNulls.put("attributes", List.of());
        payloadWithNulls.put("methods", List.of());
        // isAbstract deliberadamente ausente (ni siquiera la clave), no solo null.

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.ADD_CLASS, null, payloadWithNulls);

        assertThat(outcome.applied()).isTrue();
        ClassEntity created = outcome.model().findClass(classId).orElseThrow();
        assertThat(created.width()).isEqualTo(240.0);
        assertThat(created.height()).isEqualTo(180.0);
        assertThat(created.isAbstract()).isFalse();
    }

    @Test
    void addAttribute_fillsSchemaDefaultsWhenNumericAndBooleanFieldsAreOmitted() {
        UUID classId = UUID.randomUUID();
        ClassEntity existing = classEntity(classId, "Factura", 0, 0);
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(existing), List.of());
        UUID attributeId = UUID.randomUUID();
        // Solo los campos obligatorios; length/precision/scale/isPrimaryKey/isNullable/
        // isUnique quedan totalmente ausentes, igual que un payload real de Gemini que
        // solo menciona "atributo total decimal" sin detallar cada modificador.
        Map<String, Object> payload = Map.of(
                "id", attributeId.toString(), "name", "total", "type", "DECIMAL", "visibility", "PRIVATE");

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.ADD_ATTRIBUTE, classId, payload);

        assertThat(outcome.applied()).isTrue();
        Attribute created = outcome.model().findClass(classId).orElseThrow().attributes().stream()
                .filter(a -> a.id().equals(attributeId)).findFirst().orElseThrow();
        assertThat(created.length()).isEqualTo(255);
        assertThat(created.precision()).isEqualTo(10);
        assertThat(created.scale()).isEqualTo(2);
        assertThat(created.isPrimaryKey()).isFalse();
        assertThat(created.isNullable()).isTrue();
        assertThat(created.isUnique()).isFalse();
    }

    @Test
    void addRelationship_fillsSchemaDefaultsWhenOwningSideAndIsNavigableAreOmitted() {
        CanonicalModel model = CanonicalModel.empty();
        UUID relId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "id", relId.toString(), "sourceClassId", UUID.randomUUID().toString(),
                "targetClassId", UUID.randomUUID().toString(), "type", "ASSOCIATION");

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(
                model, OperationType.ADD_RELATIONSHIP, null, payload);

        assertThat(outcome.applied()).isTrue();
        Relationship created = outcome.model().relationships().get(0);
        assertThat(created.owningSide()).isEqualTo(OwningSide.SOURCE);
        assertThat(created.isNavigable()).isTrue();
    }

    @Test
    void bulkMerge_fillsSchemaDefaultsPerElement_forADraftMissingWidthOnOneOfSeveralClasses() {
        // UC10 (vision): el borrador puede traer varias clases y omitir width/height
        // en cualquiera de ellas, no solo en la primera.
        CanonicalModel model = CanonicalModel.empty();
        UUID completeClassId = UUID.randomUUID();
        UUID incompleteClassId = UUID.randomUUID();
        Map<String, Object> completeClass = Map.of(
                "id", completeClassId.toString(), "name", "Cliente", "visibility", "PUBLIC",
                "isAbstract", false, "position", Map.of("x", 0.0, "y", 0.0),
                "width", 240.0, "height", 180.0, "attributes", List.of(), "methods", List.of());
        Map<String, Object> incompleteClass = new java.util.HashMap<>();
        incompleteClass.put("id", incompleteClassId.toString());
        incompleteClass.put("name", "Factura");
        incompleteClass.put("visibility", "PUBLIC");
        incompleteClass.put("position", Map.of("x", 300.0, "y", 0.0));
        incompleteClass.put("width", null);
        incompleteClass.put("height", null);
        incompleteClass.put("attributes", List.of());
        incompleteClass.put("methods", List.of());
        Map<String, Object> payload = Map.of("classes", List.of(completeClass, incompleteClass));

        CanonicalModelMutator.MutationOutcome outcome = mutator.apply(model, OperationType.BULK_MERGE, null, payload);

        assertThat(outcome.applied()).isTrue();
        ClassEntity incomplete = outcome.model().findClass(incompleteClassId).orElseThrow();
        assertThat(incomplete.width()).isEqualTo(240.0);
        assertThat(incomplete.height()).isEqualTo(180.0);
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
