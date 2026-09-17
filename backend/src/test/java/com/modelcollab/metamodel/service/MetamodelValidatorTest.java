package com.modelcollab.metamodel.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un test por cada invariante del catalogo de la seccion 11.2, tal como pide
 * la seccion 4.1 del documento de arquitectura.
 */
class MetamodelValidatorTest {

    private final MetamodelValidator validator = new MetamodelValidator(new IdentifierSanitizer());

    @Test
    void validate_returnsValidWithNoIssues_forWellFormedModel() {
        ClassEntity clazz = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cliente")
                .position(new Position(0, 0))
                .attributes(List.of(pkAttribute("id")))
                .build();
        CanonicalModel model = modelWith(List.of(clazz), List.of());

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void validate_warnsOnSqlReservedWordInClassName() {
        ClassEntity clazz = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Order")
                .position(new Position(0, 0))
                .attributes(List.of(pkAttribute("id")))
                .build();
        CanonicalModel model = modelWith(List.of(clazz), List.of());

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
                    assertThat(issue.code()).isEqualTo("SQL_RESERVED_WORD");
                });
    }

    @Test
    void validate_warnsOnJavaReservedWordInAttributeName() {
        ClassEntity clazz = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cliente")
                .position(new Position(0, 0))
                .attributes(List.of(pkAttribute("id"),
                        Attribute.builder().id(UUID.randomUUID()).name("class")
                                .type(AttributeType.VARCHAR).visibility(Visibility.PRIVATE).build()))
                .build();
        CanonicalModel model = modelWith(List.of(clazz), List.of());

        ValidationResult result = validator.validate(model);

        assertThat(result.issues())
                .anySatisfy(issue -> assertThat(issue.code()).isEqualTo("JAVA_RESERVED_WORD"));
    }

    @Test
    void validate_warnsWhenClassHasNoPrimaryKey() {
        ClassEntity clazz = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cliente")
                .position(new Position(0, 0))
                .attributes(List.of(Attribute.builder().id(UUID.randomUUID()).name("nombre")
                        .type(AttributeType.VARCHAR).visibility(Visibility.PRIVATE).build()))
                .build();
        CanonicalModel model = modelWith(List.of(clazz), List.of());

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
                    assertThat(issue.code()).isEqualTo("NO_PRIMARY_KEY");
                });
    }

    @Test
    void validate_rejectsMultipleInheritanceAsBlockingError() {
        UUID childId = UUID.randomUUID();
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        ClassEntity child = classWithPk(childId, "Hijo");
        ClassEntity a = classWithPk(parentA, "PadreA");
        ClassEntity b = classWithPk(parentB, "PadreB");

        Relationship inheritanceA = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(childId).targetClassId(parentA).type(RelationshipType.GENERALIZATION).build();
        Relationship inheritanceB = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(childId).targetClassId(parentB).type(RelationshipType.GENERALIZATION).build();

        CanonicalModel model = modelWith(List.of(child, a, b), List.of(inheritanceA, inheritanceB));

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
                    assertThat(issue.code()).isEqualTo("MULTIPLE_GENERALIZATION");
                });
    }

    @Test
    void validate_allowsSingleInheritance() {
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        ClassEntity child = classWithPk(childId, "Hijo");
        ClassEntity parent = classWithPk(parentId, "Padre");
        Relationship inheritance = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(childId).targetClassId(parentId).type(RelationshipType.GENERALIZATION).build();

        CanonicalModel model = modelWith(List.of(child, parent), List.of(inheritance));

        ValidationResult result = validator.validate(model);

        assertThat(result.issues()).noneMatch(issue -> issue.code().equals("MULTIPLE_GENERALIZATION"));
    }

    @Test
    void validate_rejectsManyToManyWithoutOwningSide_asBlockingError() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ClassEntity source = classWithPk(sourceId, "Alumno");
        ClassEntity target = classWithPk(targetId, "Curso");
        Relationship relationship = new Relationship(UUID.randomUUID(), sourceId, targetId,
                RelationshipType.MANY_TO_MANY, null, "alumno_curso", null, null, null, null, true, List.of());

        CanonicalModel model = modelWith(List.of(source, target), List.of(relationship));

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
                    assertThat(issue.code()).isEqualTo("MANY_TO_MANY_OWNING_SIDE_MISSING");
                });
    }

    @Test
    void validate_warnsOnManyToManyWithoutJoinTableName() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ClassEntity source = classWithPk(sourceId, "Alumno");
        ClassEntity target = classWithPk(targetId, "Curso");
        Relationship relationship = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(sourceId).targetClassId(targetId)
                .type(RelationshipType.MANY_TO_MANY).owningSide(OwningSide.SOURCE).build();

        CanonicalModel model = modelWith(List.of(source, target), List.of(relationship));

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
                    assertThat(issue.code()).isEqualTo("MANY_TO_MANY_JOIN_TABLE_MISSING");
                });
    }

    @Test
    void validate_rejectsOrphanRelationship_asBlockingError() {
        UUID sourceId = UUID.randomUUID();
        ClassEntity source = classWithPk(sourceId, "Factura");
        UUID nonExistentTargetId = UUID.randomUUID();
        Relationship orphan = Relationship.builder().id(UUID.randomUUID())
                .sourceClassId(sourceId).targetClassId(nonExistentTargetId)
                .type(RelationshipType.ASSOCIATION).build();

        CanonicalModel model = modelWith(List.of(source), List.of(orphan));

        ValidationResult result = validator.validate(model);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
                    assertThat(issue.code()).isEqualTo("ORPHAN_RELATIONSHIP");
                });
    }

    private static ClassEntity classWithPk(UUID id, String name) {
        return ClassEntity.builder().id(id).name(name).position(new Position(0, 0))
                .attributes(List.of(pkAttribute("id"))).build();
    }

    private static Attribute pkAttribute(String name) {
        return Attribute.builder().id(UUID.randomUUID()).name(name)
                .type(AttributeType.UUID).visibility(Visibility.PRIVATE).primaryKey(true).build();
    }

    private static CanonicalModel modelWith(List<ClassEntity> classes, List<Relationship> relationships) {
        return new CanonicalModel("1.0.0", 1, List.of(), classes, relationships);
    }
}
