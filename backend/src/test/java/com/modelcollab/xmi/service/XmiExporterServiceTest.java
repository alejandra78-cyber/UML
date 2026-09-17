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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitario de logica pura (sin contexto Spring) para {@link XmiExporterService}:
 * verifica los namespaces OMG XMI 2.1 / UML 2.1 exigidos por
 * {@code PLAN_ARQUITECTONICO.md} y que clases, atributos, metodos y relaciones
 * de un {@link CanonicalModel} de prueba aparecen en el XML resultante con la
 * estructura esperada (incluyendo layout: position/waypoints).
 */
class XmiExporterServiceTest {

    private final XmiExporterService exporter = new XmiExporterService();

    @Test
    void exportToXmi_includesOmgXmiAndUmlNamespaces() {
        String xml = exporter.exportToXmi(CanonicalModel.empty(), "Diagrama Vacio");

        assertThat(xml).contains("xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\"");
        assertThat(xml).contains("xmlns:uml=\"http://schema.omg.org/spec/UML/2.1\"");
        assertThat(xml).contains("xmi:version=\"2.1\"");
        assertThat(xml).contains("<uml:Model");
    }

    @Test
    void exportToXmi_serializesPackageAsUmlPackage() {
        UUID classId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        ClassEntity classEntity = minimalClass(classId, "Cliente");
        Package pkg = new Package(packageId, "com.example.dominio", List.of(classId));
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(pkg), List.of(classEntity), List.of());

        String xml = exporter.exportToXmi(model, "Diagrama");

        assertThat(xml).contains("<uml:Package");
        assertThat(xml).contains("xmi:id=\"" + packageId + "\"");
        assertThat(xml).contains("name=\"com.example.dominio\"");
    }

    @Test
    void exportToXmi_serializesClassWithAttributesMethodsAndLayout() {
        UUID classId = UUID.randomUUID();
        UUID attributeId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();

        Attribute attribute = Attribute.builder()
                .id(attributeId)
                .name("nombre")
                .type(AttributeType.VARCHAR)
                .visibility(Visibility.PRIVATE)
                .primaryKey(false)
                .build();

        Method method = new Method(methodId, "saludar", "void", Visibility.PUBLIC,
                List.of(new Parameter("mensaje", "String")));

        ClassEntity classEntity = ClassEntity.builder()
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

        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(classEntity), List.of());

        String xml = exporter.exportToXmi(model, "Diagrama");

        assertThat(xml).contains("xmi:type=\"uml:Class\"");
        assertThat(xml).contains("xmi:id=\"" + classId + "\"");
        assertThat(xml).contains("name=\"Cliente\"");

        assertThat(xml).contains("<ownedAttribute");
        assertThat(xml).contains("xmi:id=\"" + attributeId + "\"");
        assertThat(xml).contains("name=\"nombre\"");
        assertThat(xml).contains("type=\"VARCHAR\"");

        assertThat(xml).contains("<ownedOperation");
        assertThat(xml).contains("xmi:id=\"" + methodId + "\"");
        assertThat(xml).contains("name=\"saludar\"");
        assertThat(xml).contains("<ownedParameter");
        assertThat(xml).contains("name=\"mensaje\"");
        assertThat(xml).contains("type=\"String\"");

        assertThat(xml).contains("<position");
        assertThat(xml).contains("x=\"15.0\"");
        assertThat(xml).contains("y=\"42.0\"");
        assertThat(xml).contains("width=\"240.0\"");
        assertThat(xml).contains("height=\"180.0\"");
    }

    @Test
    void exportToXmi_serializesRelationshipWithMultiplicitiesRolesAndWaypoints() {
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

        String xml = exporter.exportToXmi(model, "Diagrama");

        assertThat(xml).contains("xmi:type=\"uml:Association\"");
        assertThat(xml).contains("xmi:id=\"" + relationshipId + "\"");
        assertThat(xml).contains("relationshipType=\"ASSOCIATION\"");

        assertThat(xml).contains("kind=\"source\"");
        assertThat(xml).contains("type=\"" + sourceId + "\"");
        assertThat(xml).contains("role=\"pedidos\"");
        assertThat(xml).contains("multiplicity=\"0..*\"");

        assertThat(xml).contains("kind=\"target\"");
        assertThat(xml).contains("type=\"" + targetId + "\"");
        assertThat(xml).contains("role=\"cliente\"");
        assertThat(xml).contains("multiplicity=\"1..1\"");

        // Limites estandar OMG UML (lowerValue/upperValue), ademas del atributo
        // "multiplicity" compacto.
        assertThat(xml).contains("<lowerValue value=\"0\" xmi:type=\"uml:LiteralInteger\"");
        assertThat(xml).contains("<upperValue value=\"*\" xmi:type=\"uml:LiteralUnlimitedNatural\"");
        assertThat(xml).contains("<lowerValue value=\"1\" xmi:type=\"uml:LiteralInteger\"");
        assertThat(xml).contains("<upperValue value=\"1\" xmi:type=\"uml:LiteralInteger\"");

        assertThat(xml).contains("<waypoints>");
        assertThat(xml).contains("<waypoint");
        assertThat(xml).contains("x=\"10.0\"");
        assertThat(xml).contains("y=\"20.0\"");
        assertThat(xml).contains("x=\"30.0\"");
        assertThat(xml).contains("y=\"40.0\"");
    }

    @Test
    void exportToXmi_mapsGeneralizationToUmlGeneralization() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(sourceId)
                .targetClassId(targetId)
                .type(RelationshipType.GENERALIZATION)
                .build();
        ClassEntity source = minimalClass(sourceId, "Empleado");
        ClassEntity target = minimalClass(targetId, "Persona");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(source, target), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");

        assertThat(xml).contains("xmi:type=\"uml:Generalization\"");
    }

    private static ClassEntity minimalClass(UUID id, String name) {
        return ClassEntity.builder()
                .id(id)
                .name(name)
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .build();
    }
}
