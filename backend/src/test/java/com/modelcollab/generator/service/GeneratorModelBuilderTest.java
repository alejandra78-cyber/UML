package com.modelcollab.generator.service;

import com.modelcollab.generator.service.model.ClassView;
import com.modelcollab.generator.service.model.DtoFieldView;
import com.modelcollab.generator.service.model.RelationshipFieldView;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos bugs reales encontrados al generar y COMPILAR un backend real (dominio
 * veterinaria: Mascota/Receta/Vacuna -> Veterinario), ambos con la misma causa de
 * fondo: cuando la clase referenciada por una relación {@code @ManyToOne}/
 * {@code @OneToOne} dueña declara su propia PK (en vez de la inyectada
 * automáticamente, ver invariante 2 en {@code buildClassView}):
 * <ol>
 *   <li>El RequestDTO/ResponseDTO del lado dueño exponía el id de esa FK siempre
 *   como {@code Long}, sin importar el tipo real de la PK referenciada (p.ej.
 *   {@code Integer}) -- el service generado no compilaba
 *   ({@code veterinarioRepository.findById(Long)} contra un
 *   {@code JpaRepository<Veterinario, Integer>}).</li>
 *   <li>El Mapper generado llamaba siempre a {@code entity.getId()} sobre la
 *   entidad relacionada, pero una PK declarada con un nombre distinto de "id"
 *   (p.ej. "idVeterinario") genera el getter {@code getIdVeterinario()}, no
 *   {@code getId()} -- "cannot find symbol: method getId()".</li>
 * </ol>
 *
 * <p>Cubre ambos fixes directamente sobre {@link GeneratorModelBuilder} (rápido,
 * sin invocar Maven) -- {@link SpringBootGeneratorServiceSmokeTest} cubre el mismo
 * escenario de punta a punta compilando de verdad, pero es lento a propósito.</p>
 */
class GeneratorModelBuilderTest {

    private final GeneratorModelBuilder builder = new GeneratorModelBuilder(new IdentifierSanitizer());

    @Test
    void manyToOne_targetWithDeclaredNonLongPrimaryKey_dtoFkFieldUsesSameType() {
        ClassEntity veterinario = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Veterinario")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        Attribute.builder().id(UUID.randomUUID()).name("id").type(AttributeType.INTEGER)
                                .visibility(Visibility.PRIVATE).primaryKey(true).nullable(false).build(),
                        Attribute.builder().id(UUID.randomUUID()).name("nombre").type(AttributeType.VARCHAR)
                                .visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        ClassEntity cita = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cita")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        Attribute.builder().id(UUID.randomUUID()).name("fecha").type(AttributeType.DATE)
                                .visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        Relationship citaVeterinario = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(cita.id())
                .targetClassId(veterinario.id())
                .type(RelationshipType.ASSOCIATION)
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ONE_ONE)
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(veterinario, cita), List.of(citaVeterinario));

        List<ClassView> views = builder.build(model);
        ClassView veterinarioView = findByName(views, "Veterinario");
        ClassView citaView = findByName(views, "Cita");

        assertThat(veterinarioView.getEffectivePrimaryKeyType())
                .as("sanity check: Veterinario declaró su PK como INTEGER")
                .isEqualTo("Integer");

        DtoFieldView requestFk = findFieldNamed(citaView.getRequestDtoFields(), "veterinarioId");
        DtoFieldView responseFk = findFieldNamed(citaView.getResponseDtoFields(), "veterinarioId");

        assertThat(requestFk.getJavaType())
                .as("CitaRequestDTO.veterinarioId debe matchear el tipo real de la PK de Veterinario")
                .isEqualTo("Integer");
        assertThat(responseFk.getJavaType())
                .as("CitaResponseDTO.veterinarioId debe matchear el tipo real de la PK de Veterinario")
                .isEqualTo("Integer");
    }

    @Test
    void manyToOne_targetWithCustomNamedPrimaryKey_mapperCallsRealGetterName() {
        ClassEntity veterinario = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Veterinario")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        // PK con nombre NO default ("idVeterinario", no "id"): el bug real era
                        // que el Mapper llamaba siempre a entity.getId() sobre la entidad
                        // relacionada, sin importar el nombre real del getter de su PK.
                        Attribute.builder().id(UUID.randomUUID()).name("idVeterinario").type(AttributeType.BIGINT)
                                .visibility(Visibility.PRIVATE).primaryKey(true).nullable(false).build(),
                        Attribute.builder().id(UUID.randomUUID()).name("nombre").type(AttributeType.VARCHAR)
                                .visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        ClassEntity cita = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cita")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        Attribute.builder().id(UUID.randomUUID()).name("fecha").type(AttributeType.DATE)
                                .visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        Relationship citaVeterinario = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(cita.id())
                .targetClassId(veterinario.id())
                .type(RelationshipType.ASSOCIATION)
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ONE_ONE)
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(veterinario, cita), List.of(citaVeterinario));

        ClassView citaView = findByName(builder.build(model), "Cita");
        RelationshipFieldView rf = citaView.getOwningSingleValuedRelationships().stream()
                .filter(r -> r.getFieldName().equals("veterinario")).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontró la relación 'veterinario' en Cita"));

        assertThat(rf.getTargetIdGetterName())
                .as("el Mapper de Cita debe llamar al getter real de la PK de Veterinario, no getId() a ciegas")
                .isEqualTo("getIdVeterinario");
    }

    @Test
    void manyToOne_targetWithoutDeclaredPrimaryKey_dtoFkFieldDefaultsToLong() {
        // Caso ya cubierto antes del bug (invariante 2: PK auto-inyectada como Long
        // cuando ninguna esta marcada) -- no debe romperse con el fix.
        ClassEntity dueno = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Dueno")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(Attribute.builder().id(UUID.randomUUID()).name("nombre")
                        .type(AttributeType.VARCHAR).visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        ClassEntity mascota = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Mascota")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(Attribute.builder().id(UUID.randomUUID()).name("nombre")
                        .type(AttributeType.VARCHAR).visibility(Visibility.PRIVATE).nullable(false).build()))
                .build();
        Relationship mascotaDueno = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(mascota.id())
                .targetClassId(dueno.id())
                .type(RelationshipType.ASSOCIATION)
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ONE_ONE)
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(dueno, mascota), List.of(mascotaDueno));

        List<ClassView> views = builder.build(model);
        ClassView mascotaView = findByName(views, "Mascota");

        assertThat(findFieldNamed(mascotaView.getRequestDtoFields(), "duenoId").getJavaType()).isEqualTo("Long");
        assertThat(findFieldNamed(mascotaView.getResponseDtoFields(), "duenoId").getJavaType()).isEqualTo("Long");
    }

    private static ClassView findByName(List<ClassView> views, String className) {
        return views.stream().filter(v -> v.getClassName().equals(className)).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontró ClassView para " + className));
    }

    private static DtoFieldView findFieldNamed(List<DtoFieldView> fields, String fieldName) {
        return fields.stream().filter(f -> f.getJavaFieldName().equals(fieldName)).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontró el campo DTO " + fieldName));
    }
}
