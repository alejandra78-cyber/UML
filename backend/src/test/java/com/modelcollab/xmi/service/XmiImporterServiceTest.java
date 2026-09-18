package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Package;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.model.Waypoint;
import com.modelcollab.metamodel.service.GridLayoutEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UC16 -- Importar Diagrama desde XMI. Test unitario de lógica pura (sin
 * contexto Spring), en el mismo estilo que {@link XmiExporterServiceTest}.
 *
 * <p><b>Cobertura:</b></p>
 * <ol>
 *   <li>Round-trip exportar (UC15, {@link XmiExporterService}, ya existente y
 *   probado) → importar ({@link XmiImporterService}): el modelo resultante debe
 *   ser equivalente al original (clases, atributos, métodos, relaciones con
 *   multiplicidades/roles, posiciones y waypoints preservados).</li>
 *   <li>Casos con XML "genérico" simplificado, sin las extensiones propias
 *   {@code position}/{@code waypoints}, para probar el fallback de auto-layout
 *   vía {@link GridLayoutEngine} y la inferencia de tipo de relación desde
 *   {@code xmi:type} cuando no está el atributo custom {@code relationshipType}.</li>
 * </ol>
 *
 * <p><b>LIMITACIÓN, ver también el javadoc de {@link XmiImporterService}:</b>
 * ninguno de estos casos es un archivo real exportado desde Sparx Enterprise
 * Architect -- no existe ninguno disponible en este entorno/repositorio. El
 * caso "genérico" de este test es una aproximación manual al dialecto OMG
 * UML/XMI estándar (atributo {@code aggregation} en los extremos,
 * {@code lowerValue}/{@code upperValue} sin el atributo compacto
 * {@code multiplicity}, sin {@code position}/{@code waypoints}), no una prueba
 * contra EA real.</p>
 */
class XmiImporterServiceTest {

    private final XmiExporterService exporter = new XmiExporterService();
    private final XmiImporterService importer = new XmiImporterService(new GridLayoutEngine());

    // ------------------------------------------------------------------
    // 1. Round-trip contra nuestro propio exportador (UC15).
    // ------------------------------------------------------------------

    @Test
    void roundTrip_emptyModel_importsBackToEmptyModel() {
        CanonicalModel original = CanonicalModel.empty();

        CanonicalModel imported = exportThenImport(original, "Diagrama Vacio").model();

        assertThat(imported.classes()).isEmpty();
        assertThat(imported.packages()).isEmpty();
        assertThat(imported.relationships()).isEmpty();
    }

    @Test
    void roundTrip_preservesDiagramNameFromUmlModel() {
        XmiImporterService.ImportResult result = exportThenImport(CanonicalModel.empty(), "Mi Diagrama");

        assertThat(result.diagramName()).isEqualTo("Mi Diagrama");
    }

    @Test
    void roundTrip_classWithAttributesAndMethods_isPreserved() {
        UUID classId = UUID.randomUUID();
        Attribute attribute = Attribute.builder()
                .id(UUID.randomUUID())
                .name("nombre")
                .type(AttributeType.VARCHAR)
                .visibility(Visibility.PRIVATE)
                .primaryKey(false)
                .nullable(false)
                .unique(true)
                .length(120)
                .build();
        Method method = new Method(UUID.randomUUID(), "saludar", "void", Visibility.PUBLIC,
                List.of(new Parameter("mensaje", "String")));
        ClassEntity original = ClassEntity.builder()
                .id(classId)
                .name("Cliente")
                .visibility(Visibility.PUBLIC)
                .isAbstract(false)
                .position(new Position(15.0, 42.0))
                .width(240)
                .height(180)
                .attributes(List.of(attribute))
                .methods(List.of(method))
                .build();
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(original), List.of());

        CanonicalModel imported = exportThenImport(model, "Diagrama").model();

        assertThat(imported.classes()).hasSize(1);
        ClassEntity importedClass = imported.classes().get(0);
        assertThat(importedClass.id()).isEqualTo(classId);
        assertThat(importedClass.name()).isEqualTo("Cliente");
        assertThat(importedClass.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(importedClass.isAbstract()).isFalse();
        assertThat(importedClass.position()).isEqualTo(new Position(15.0, 42.0));
        assertThat(importedClass.width()).isEqualTo(240);
        assertThat(importedClass.height()).isEqualTo(180);

        assertThat(importedClass.attributes()).hasSize(1);
        Attribute importedAttribute = importedClass.attributes().get(0);
        assertThat(importedAttribute.id()).isEqualTo(attribute.id());
        assertThat(importedAttribute.name()).isEqualTo("nombre");
        assertThat(importedAttribute.type()).isEqualTo(AttributeType.VARCHAR);
        assertThat(importedAttribute.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(importedAttribute.isNullable()).isFalse();
        assertThat(importedAttribute.isUnique()).isTrue();
        assertThat(importedAttribute.length()).isEqualTo(120);

        assertThat(importedClass.methods()).hasSize(1);
        Method importedMethod = importedClass.methods().get(0);
        assertThat(importedMethod.id()).isEqualTo(method.id());
        assertThat(importedMethod.name()).isEqualTo("saludar");
        assertThat(importedMethod.returnType()).isEqualTo("void");
        assertThat(importedMethod.parameters()).containsExactly(new Parameter("mensaje", "String"));
    }

    @Test
    void roundTrip_packageWithClasses_isPreserved() {
        UUID classId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        ClassEntity classEntity = minimalClass(classId, "Cliente");
        Package pkg = new Package(packageId, "com.example.dominio", List.of(classId));
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(pkg), List.of(classEntity), List.of());

        CanonicalModel imported = exportThenImport(model, "Diagrama").model();

        assertThat(imported.packages()).hasSize(1);
        Package importedPackage = imported.packages().get(0);
        assertThat(importedPackage.id()).isEqualTo(packageId);
        assertThat(importedPackage.name()).isEqualTo("com.example.dominio");
        assertThat(importedPackage.classIds()).containsExactly(classId);
        assertThat(imported.classes()).extracting(ClassEntity::id).containsExactly(classId);
    }

    @Test
    void roundTrip_relationshipWithMultiplicitiesRolesAndWaypoints_isPreserved() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();

        ClassEntity source = minimalClass(sourceId, "Pedido");
        ClassEntity target = minimalClass(targetId, "Cliente");

        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(sourceId)
                .targetClassId(targetId)
                .type(RelationshipType.ASSOCIATION)
                .owningSide(OwningSide.SOURCE)
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ONE_ONE)
                .sourceRole("pedidos")
                .targetRole("cliente")
                .isNavigable(true)
                .waypoints(List.of(new Waypoint(10, 20), new Waypoint(30, 40)))
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(source, target), List.of(relationship));

        CanonicalModel imported = exportThenImport(model, "Diagrama").model();

        assertThat(imported.relationships()).hasSize(1);
        Relationship importedRelationship = imported.relationships().get(0);
        assertThat(importedRelationship.id()).isEqualTo(relationshipId);
        assertThat(importedRelationship.type()).isEqualTo(RelationshipType.ASSOCIATION);
        assertThat(importedRelationship.sourceClassId()).isEqualTo(sourceId);
        assertThat(importedRelationship.targetClassId()).isEqualTo(targetId);
        assertThat(importedRelationship.owningSide()).isEqualTo(OwningSide.SOURCE);
        assertThat(importedRelationship.sourceMultiplicity()).isEqualTo(Multiplicity.ZERO_MANY);
        assertThat(importedRelationship.targetMultiplicity()).isEqualTo(Multiplicity.ONE_ONE);
        assertThat(importedRelationship.sourceRole()).isEqualTo("pedidos");
        assertThat(importedRelationship.targetRole()).isEqualTo("cliente");
        assertThat(importedRelationship.isNavigable()).isTrue();
        assertThat(importedRelationship.waypoints()).containsExactly(new Waypoint(10, 20), new Waypoint(30, 40));
    }

    @Test
    void roundTrip_generalizationAndManyToMany_mapTypesBack() {
        UUID empleadoId = UUID.randomUUID();
        UUID personaId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();
        UUID proveedorId = UUID.randomUUID();

        Relationship generalization = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(empleadoId)
                .targetClassId(personaId)
                .type(RelationshipType.GENERALIZATION)
                .build();
        Relationship manyToMany = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(productoId)
                .targetClassId(proveedorId)
                .type(RelationshipType.MANY_TO_MANY)
                .owningSide(OwningSide.SOURCE)
                .joinTableName("producto_proveedor")
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ZERO_MANY)
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(minimalClass(empleadoId, "Empleado"), minimalClass(personaId, "Persona"),
                        minimalClass(productoId, "Producto"), minimalClass(proveedorId, "Proveedor")),
                List.of(generalization, manyToMany));

        CanonicalModel imported = exportThenImport(model, "Diagrama").model();

        Map<UUID, Relationship> byId = imported.relationships().stream()
                .collect(Collectors.toMap(Relationship::id, r -> r));
        assertThat(byId.get(generalization.id()).type()).isEqualTo(RelationshipType.GENERALIZATION);
        Relationship importedM2m = byId.get(manyToMany.id());
        assertThat(importedM2m.type()).isEqualTo(RelationshipType.MANY_TO_MANY);
        assertThat(importedM2m.joinTableName()).isEqualTo("producto_proveedor");
    }

    @Test
    void roundTrip_dependencyAndRealization_preserveSourceAndTargetClassIds() {
        // Cierra el ciclo completo (no solo el lado exportador) para los dos tipos
        // que ahora usan client/supplier en vez de ownedEnd -- confirma que
        // sourceClassId/targetClassId sobreviven el export->import, no solo el
        // xmi:type.
        UUID facturacionId = UUID.randomUUID();
        UUID emailId = UUID.randomUUID();
        UUID repositorioJpaId = UUID.randomUUID();
        UUID repositorioId = UUID.randomUUID();

        Relationship dependency = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(facturacionId)
                .targetClassId(emailId)
                .type(RelationshipType.DEPENDENCY)
                .build();
        Relationship realization = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(repositorioJpaId)
                .targetClassId(repositorioId)
                .type(RelationshipType.REALIZATION)
                .build();

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(minimalClass(facturacionId, "ServicioFacturacion"), minimalClass(emailId, "ServicioEmail"),
                        minimalClass(repositorioJpaId, "RepositorioJpa"), minimalClass(repositorioId, "Repositorio")),
                List.of(dependency, realization));

        CanonicalModel imported = exportThenImport(model, "Diagrama").model();

        Map<UUID, Relationship> byId = imported.relationships().stream()
                .collect(Collectors.toMap(Relationship::id, r -> r));
        Relationship importedDependency = byId.get(dependency.id());
        assertThat(importedDependency.type()).isEqualTo(RelationshipType.DEPENDENCY);
        assertThat(importedDependency.sourceClassId()).isEqualTo(facturacionId);
        assertThat(importedDependency.targetClassId()).isEqualTo(emailId);

        Relationship importedRealization = byId.get(realization.id());
        assertThat(importedRealization.type()).isEqualTo(RelationshipType.REALIZATION);
        assertThat(importedRealization.sourceClassId()).isEqualTo(repositorioJpaId);
        assertThat(importedRealization.targetClassId()).isEqualTo(repositorioId);
    }

    @Test
    void roundTrip_fullDomainModel_isFullyEquivalent() {
        // Reusa un dominio con clases, atributos, métodos, herencia, asociación
        // y many-to-many -- ejercitando todos los caminos del importador a la vez.
        ClassEntity persona = ClassEntity.builder()
                .id(UUID.randomUUID()).name("Persona").visibility(Visibility.PUBLIC)
                .isAbstract(true).position(new Position(0, 0))
                .attributes(List.of(pk("id"), attr("nombre", AttributeType.VARCHAR, false)))
                .build();
        ClassEntity empleado = ClassEntity.builder()
                .id(UUID.randomUUID()).name("Empleado").visibility(Visibility.PUBLIC)
                .position(new Position(300, 0))
                .attributes(List.of(attr("salario", AttributeType.DECIMAL, false)))
                .methods(List.of(new Method(UUID.randomUUID(), "calcularBono", "BigDecimal", Visibility.PUBLIC, List.of())))
                .build();
        ClassEntity departamento = ClassEntity.builder()
                .id(UUID.randomUUID()).name("Departamento").visibility(Visibility.PUBLIC)
                .position(new Position(600, 0))
                .attributes(List.of(pk("id"), attr("nombre", AttributeType.VARCHAR, false)))
                .build();

        Relationship herencia = Relationship.builder()
                .id(UUID.randomUUID()).sourceClassId(empleado.id()).targetClassId(persona.id())
                .type(RelationshipType.GENERALIZATION).build();
        Relationship asociacion = Relationship.builder()
                .id(UUID.randomUUID()).sourceClassId(empleado.id()).targetClassId(departamento.id())
                .type(RelationshipType.ASSOCIATION)
                .sourceMultiplicity(Multiplicity.ZERO_MANY).targetMultiplicity(Multiplicity.ONE_ONE)
                .sourceRole("empleados").targetRole("departamento")
                .waypoints(List.of(new Waypoint(100, 50)))
                .build();

        CanonicalModel original = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(persona, empleado, departamento), List.of(herencia, asociacion));

        CanonicalModel imported = exportThenImport(original, "Dominio RRHH").model();

        assertModelsEquivalent(original, imported);
    }

    // ------------------------------------------------------------------
    // 2. Dialecto "generico" simplificado (sin position/waypoints propios).
    // ------------------------------------------------------------------

    @Test
    void genericXmi_withoutPositionOrWaypoints_usesGridLayoutEngineFallback() {
        String genericXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model-1" name="Generico">
                    <packagedElement xmi:type="uml:Class" xmi:id="EAID_CLIENTE" name="Cliente" visibility="public">
                      <ownedAttribute xmi:id="EAID_ATTR_NOMBRE" name="nombre" type="VARCHAR" visibility="private" isNullable="false"/>
                    </packagedElement>
                    <packagedElement xmi:type="uml:Class" xmi:id="EAID_PEDIDO" name="Pedido" visibility="public">
                      <ownedAttribute xmi:id="EAID_ATTR_FECHA" name="fecha" type="DATE" visibility="private"/>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """;

        CanonicalModel model = importer.importXmi(genericXml).model();

        assertThat(model.classes()).hasSize(2);
        // Ninguna clase traía <position>: GridLayoutEngine debe haberles asignado
        // posiciones distintas (no ambas en (0,0), y no solapadas entre si).
        List<Position> positions = model.classes().stream().map(ClassEntity::position).toList();
        assertThat(positions.get(0)).isNotEqualTo(positions.get(1));

        ClassEntity cliente = findByName(model, "Cliente");
        assertThat(cliente.attributes()).hasSize(1);
        assertThat(cliente.attributes().get(0).name()).isEqualTo("nombre");
        assertThat(cliente.attributes().get(0).type()).isEqualTo(AttributeType.VARCHAR);
        assertThat(cliente.attributes().get(0).isNullable()).isFalse();

        ClassEntity pedido = findByName(model, "Pedido");
        assertThat(pedido.attributes().get(0).type()).isEqualTo(AttributeType.DATE);
    }

    @Test
    void genericXmi_associationWithAggregationAttribute_inferredAsComposition() {
        String genericXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model-1" name="Generico">
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_A" name="Pedido" visibility="public"/>
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_B" name="LineaPedido" visibility="public"/>
                    <packagedElement xmi:type="uml:Association" xmi:id="ID_REL">
                      <ownedEnd kind="source" type="ID_A" role="pedido">
                        <lowerValue value="1" xmi:type="uml:LiteralInteger"/>
                        <upperValue value="1" xmi:type="uml:LiteralInteger"/>
                      </ownedEnd>
                      <ownedEnd kind="target" type="ID_B" role="lineas" aggregation="composite">
                        <lowerValue value="0" xmi:type="uml:LiteralInteger"/>
                        <upperValue value="*" xmi:type="uml:LiteralUnlimitedNatural"/>
                      </ownedEnd>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """;

        CanonicalModel model = importer.importXmi(genericXml).model();

        assertThat(model.relationships()).hasSize(1);
        Relationship relationship = model.relationships().get(0);
        assertThat(relationship.type()).isEqualTo(RelationshipType.COMPOSITION);
        assertThat(relationship.sourceMultiplicity()).isEqualTo(Multiplicity.ONE_ONE);
        assertThat(relationship.targetMultiplicity()).isEqualTo(Multiplicity.ZERO_MANY);

        ClassEntity pedido = findByName(model, "Pedido");
        ClassEntity lineaPedido = findByName(model, "LineaPedido");
        assertThat(relationship.sourceClassId()).isEqualTo(pedido.id());
        assertThat(relationship.targetClassId()).isEqualTo(lineaPedido.id());
    }

    @Test
    void genericXmi_generalizationWithoutCustomAttribute_isInferredFromXmiType() {
        String genericXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model-1" name="Generico">
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_HIJO" name="Empleado"/>
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_PADRE" name="Persona"/>
                    <packagedElement xmi:type="uml:Generalization" xmi:id="ID_GEN">
                      <ownedEnd kind="source" type="ID_HIJO"/>
                      <ownedEnd kind="target" type="ID_PADRE"/>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """;

        CanonicalModel model = importer.importXmi(genericXml).model();

        assertThat(model.relationships()).hasSize(1);
        assertThat(model.relationships().get(0).type()).isEqualTo(RelationshipType.GENERALIZATION);
    }

    @Test
    void legacyXmi_generalizationAsTopLevelPackagedElementWithGeneralSpecificAttributes_stillImports() {
        // Compatibilidad hacia atras con el formato INTERMEDIO: nuestro propio
        // exportador, entre el fix anterior (general/specific como atributos sobre un
        // packagedElement propio) y este (generalization anidada dentro de la clase
        // hija), produjo archivos con esta forma exacta. Deben seguir importando bien.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model-1" name="Generico">
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_HIJO" name="Empleado"/>
                    <packagedElement xmi:type="uml:Class" xmi:id="ID_PADRE" name="Persona"/>
                    <packagedElement xmi:type="uml:Generalization" xmi:id="ID_GEN"
                        relationshipType="GENERALIZATION" general="ID_PADRE" specific="ID_HIJO"/>
                  </uml:Model>
                </xmi:XMI>
                """;

        CanonicalModel model = importer.importXmi(legacyXml).model();

        assertThat(model.relationships()).hasSize(1);
        Relationship relationship = model.relationships().get(0);
        assertThat(relationship.type()).isEqualTo(RelationshipType.GENERALIZATION);
        ClassEntity empleado = findByName(model, "Empleado");
        ClassEntity persona = findByName(model, "Persona");
        assertThat(relationship.sourceClassId()).isEqualTo(empleado.id());
        assertThat(relationship.targetClassId()).isEqualTo(persona.id());
    }

    @Test
    void importXmi_malformedXml_throwsXmiImportException() {
        assertThatThrownBy(() -> importer.importXmi("<not-even-xml"))
                .isInstanceOf(XmiImporterService.XmiImportException.class);
    }

    @Test
    void importXmi_missingUmlModel_throwsXmiImportException() {
        assertThatThrownBy(() -> importer.importXmi("<somethingElse/>"))
                .isInstanceOf(XmiImporterService.XmiImportException.class);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private XmiImporterService.ImportResult exportThenImport(CanonicalModel model, String diagramName) {
        String xml = exporter.exportToXmi(model, diagramName);
        return importer.importXmi(xml);
    }

    private static void assertModelsEquivalent(CanonicalModel expected, CanonicalModel actual) {
        assertThat(actual.classes()).hasSameSizeAs(expected.classes());
        Map<UUID, ClassEntity> expectedById = expected.classes().stream()
                .collect(Collectors.toMap(ClassEntity::id, c -> c));
        for (ClassEntity actualClass : actual.classes()) {
            ClassEntity expectedClass = expectedById.get(actualClass.id());
            assertThat(expectedClass).as("clase %s debe existir en el original", actualClass.id()).isNotNull();
            assertThat(actualClass.name()).isEqualTo(expectedClass.name());
            assertThat(actualClass.isAbstract()).isEqualTo(expectedClass.isAbstract());
            assertThat(actualClass.position()).isEqualTo(expectedClass.position());
            assertThat(actualClass.attributes()).hasSameSizeAs(expectedClass.attributes());
            assertThat(actualClass.methods()).hasSameSizeAs(expectedClass.methods());
        }

        assertThat(actual.relationships()).hasSameSizeAs(expected.relationships());
        Map<UUID, Relationship> expectedRelById = expected.relationships().stream()
                .collect(Collectors.toMap(Relationship::id, r -> r));
        for (Relationship actualRel : actual.relationships()) {
            Relationship expectedRel = expectedRelById.get(actualRel.id());
            assertThat(expectedRel).as("relacion %s debe existir en el original", actualRel.id()).isNotNull();
            assertThat(actualRel.type()).isEqualTo(expectedRel.type());
            assertThat(actualRel.sourceClassId()).isEqualTo(expectedRel.sourceClassId());
            assertThat(actualRel.targetClassId()).isEqualTo(expectedRel.targetClassId());
            assertThat(actualRel.sourceMultiplicity()).isEqualTo(expectedRel.sourceMultiplicity());
            assertThat(actualRel.targetMultiplicity()).isEqualTo(expectedRel.targetMultiplicity());
            assertThat(actualRel.sourceRole()).isEqualTo(expectedRel.sourceRole());
            assertThat(actualRel.targetRole()).isEqualTo(expectedRel.targetRole());
            assertThat(actualRel.waypoints()).isEqualTo(expectedRel.waypoints());
        }
    }

    private static ClassEntity findByName(CanonicalModel model, String name) {
        return model.classes().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro la clase " + name));
    }

    private static ClassEntity minimalClass(UUID id, String name) {
        return ClassEntity.builder()
                .id(id)
                .name(name)
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .build();
    }

    private static Attribute pk(String name) {
        return Attribute.builder().id(UUID.randomUUID()).name(name).type(AttributeType.BIGINT)
                .visibility(Visibility.PRIVATE).primaryKey(true).nullable(false).build();
    }

    private static Attribute attr(String name, AttributeType type, boolean nullable) {
        return Attribute.builder().id(UUID.randomUUID()).name(name).type(type)
                .visibility(Visibility.PRIVATE).nullable(nullable).build();
    }
}
