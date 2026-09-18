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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
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
    void exportToXmi_umlModelNameIsShortAndGeneric_realDiagramNameGoesInCustomAttribute() throws Exception {
        // Ajuste de UX pedido tras probar en Enterprise Architect real: EA usa
        // uml:Model/@name como prefijo de namespace visible en cada clase importada
        // (p.ej. "Diagrama de prueba::Component") -- un nombre de diagrama largo
        // ensucia esa vista. uml:Model/@name queda fijo y corto; el nombre real se
        // preserva en el atributo custom diagramName (no estandar, XmiImporterService
        // lo prioriza en un reimport propio).
        String longDiagramName = "Diagrama de prueba con un nombre bastante largo";
        String xml = exporter.exportToXmi(CanonicalModel.empty(), longDiagramName);

        Document document = parseDocument(xml);
        NodeList models = document.getElementsByTagName("uml:Model");
        assertThat(models.getLength()).isEqualTo(1);
        Element modelElement = (Element) models.item(0);

        assertThat(modelElement.getAttribute("name"))
                .as("uml:Model/@name debe ser corto y generico, no el nombre real del diagrama")
                .isEqualTo("Model");
        assertThat(modelElement.getAttribute("diagramName"))
                .as("el nombre real del diagrama debe preservarse en el atributo custom diagramName")
                .isEqualTo(longDiagramName);
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

        // Bug real encontrado con Enterprise Architect real: un atributo plano
        // type="<uuid>" (formato anterior) no es una referencia XMI valida -- EA
        // reconocia el conector pero no podia resolver sus extremos, y aparecia
        // suelto sin linea hacia ninguna clase. El formato correcto es un elemento
        // anidado <type xmi:idref="..."/> dentro de un ownedEnd tipado como
        // uml:Property.
        assertThat(xml).contains("kind=\"source\"");
        assertThat(xml).contains("<type xmi:idref=\"" + sourceId + "\"");
        assertThat(xml).contains("role=\"pedidos\"");
        assertThat(xml).contains("multiplicity=\"0..*\"");

        assertThat(xml).contains("kind=\"target\"");
        assertThat(xml).contains("<type xmi:idref=\"" + targetId + "\"");
        assertThat(xml).contains("role=\"cliente\"");
        assertThat(xml).contains("multiplicity=\"1..1\"");
        assertThat(xml).doesNotContain("type=\"" + sourceId + "\"");
        assertThat(xml).doesNotContain("type=\"" + targetId + "\"");

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

    // ------------------------------------------------------------------------
    // Tests especificos por tipo de relacion (pedido tras probar contra
    // Enterprise Architect real y confirmar que GENERALIZATION/DEPENDENCY no
    // conectaban en absoluto con la estructura ownedEnd/memberEnd de ASSOCIATION
    // -- en UML2 cada tipo tiene su propia representacion XMI, no todos siguen
    // el patron de Association). Cada test parsea con DocumentBuilder real
    // (helpers parseDocument/findRelationshipElement abajo), no substrings.
    // ------------------------------------------------------------------------

    @Test
    void exportToXmi_generalization_isNestedInsideSpecificClass_notATopLevelPackagedElement() throws Exception {
        // Segunda vuelta sobre este bug: el intento anterior (packagedElement propio
        // con general/specific como atributos planos) seguia sin conectar NINGUNA
        // Generalization en Enterprise Architect real (v15), a pesar de que los
        // xmi:idref del resto de los tipos ya resolvian correctamente. Investigacion
        // mas a fondo (confirmada contra un archivo XMI real y publico,
        // BasicTypes.uml.xmi de github.com/STIXProject/specifications): en UML2,
        // Generalization NO es una relacion de nivel superior -- es una propiedad de
        // la clase ESPECIFICA (Classifier::generalization), serializada como
        // <generalization> ANIDADO dentro del packagedElement de esa clase, con SOLO
        // xmi:id + general (specific es implicito: la clase que lo contiene).
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        // Convencion ya establecida en GeneratorModelBuilder: sourceClassId = hijo,
        // targetClassId = padre.
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(childId)
                .targetClassId(parentId)
                .type(RelationshipType.GENERALIZATION)
                .build();
        ClassEntity child = minimalClass(childId, "Empleado");
        ClassEntity parent = minimalClass(parentId, "Persona");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(child, parent), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);

        // 1. NO debe existir ningun packagedElement de nivel superior para esta
        // relacion (ni con xmi:id de la relacion, ni de xmi:type uml:Generalization) --
        // esa era exactamente la estructura descartada.
        NodeList packagedElements = document.getElementsByTagName("packagedElement");
        for (int i = 0; i < packagedElements.getLength(); i++) {
            Element candidate = (Element) packagedElements.item(i);
            assertThat(candidate.getAttribute("xmi:id"))
                    .as("Generalization NO debe aparecer como packagedElement de nivel superior")
                    .isNotEqualTo(relationshipId.toString());
            assertThat(candidate.getAttribute("xmi:type")).isNotEqualTo("uml:Generalization");
        }

        // 2. Debe existir un <generalization> anidado DENTRO del packagedElement de
        // la clase hija (Empleado), con xmi:id de la relacion y general apuntando al
        // xmi:id de la clase padre (Persona) -- specific es implicito (esta clase).
        Element childClassElement = findClassElementByXmiId(document, childId);
        NodeList nestedGeneralizations = childClassElement.getElementsByTagName("generalization");
        assertThat(nestedGeneralizations.getLength())
                .as("debe haber exactamente un <generalization> anidado dentro de la clase hija")
                .isEqualTo(1);
        Element generalizationElement = (Element) nestedGeneralizations.item(0);
        assertThat(generalizationElement.getAttribute("xmi:id")).isEqualTo(relationshipId.toString());
        assertThat(generalizationElement.getAttribute("general")).isEqualTo(parentId.toString());
        assertThat(generalizationElement.hasAttribute("specific"))
                .as("specific no existe en el estandar -- es implicito, la clase que contiene el elemento")
                .isFalse();

        // 3. La clase padre (Persona) no debe tener ningun <generalization> anidado.
        Element parentClassElement = findClassElementByXmiId(document, parentId);
        assertThat(parentClassElement.getElementsByTagName("generalization").getLength()).isZero();
    }

    @Test
    void exportToXmi_dependency_usesClientSupplierAttributes_notOwnedEnd() throws Exception {
        UUID clientClassId = UUID.randomUUID();
        UUID supplierClassId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(clientClassId)
                .targetClassId(supplierClassId)
                .type(RelationshipType.DEPENDENCY)
                .build();
        ClassEntity clientClass = minimalClass(clientClassId, "ServicioFacturacion");
        ClassEntity supplierClass = minimalClass(supplierClassId, "ServicioEmail");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(clientClass, supplierClass), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        assertThat(relationshipElement.getAttribute("xmi:type")).isEqualTo("uml:Dependency");
        assertThat(relationshipElement.getAttribute("client")).isEqualTo(clientClassId.toString());
        assertThat(relationshipElement.getAttribute("supplier")).isEqualTo(supplierClassId.toString());
        assertThat(relationshipElement.getAttribute("memberEnd")).isBlank();
        assertThat(relationshipElement.getElementsByTagName("ownedEnd").getLength()).isZero();
    }

    @Test
    void exportToXmi_realization_usesClientSupplierAttributes_notOwnedEnd() throws Exception {
        // REALIZATION no estaba en la lista explicita de 6 tipos del pedido original,
        // pero es una subclase de Dependency en UML2 y sufria el mismo problema
        // estructural -- se corrige igual, en linea con "TODOS los tipos".
        UUID implementorId = UUID.randomUUID();
        UUID interfaceId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(implementorId)
                .targetClassId(interfaceId)
                .type(RelationshipType.REALIZATION)
                .build();
        ClassEntity implementor = minimalClass(implementorId, "RepositorioJpa");
        ClassEntity interfaceClass = minimalClass(interfaceId, "Repositorio");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(),
                List.of(implementor, interfaceClass), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        assertThat(relationshipElement.getAttribute("xmi:type")).isEqualTo("uml:Realization");
        assertThat(relationshipElement.getAttribute("client")).isEqualTo(implementorId.toString());
        assertThat(relationshipElement.getAttribute("supplier")).isEqualTo(interfaceId.toString());
        assertThat(relationshipElement.getElementsByTagName("ownedEnd").getLength()).isZero();
    }

    @Test
    void exportToXmi_aggregation_wholeAtSource_getsAggregationSharedOnPartTargetEnd() throws Exception {
        UUID wholeId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        // owningSide SOURCE (default): en NUESTRA notacion el "todo" (rombo) va en
        // source -- misma convencion que web/src/features/modelado-manual/
        // UmlRelationshipEdge.tsx (wholeEnd: owningSide == TARGET ? 'target' : 'source').
        // PERO Sparx EA dibuja el rombo del lado CONTRARIO al ownedEnd marcado con
        // aggregation="shared" (confirmado con EA real, ver comentario en
        // XmiExporterService.buildAssociationEnds) -- por eso ese valor va en el
        // extremo "parte" (target), no en el "todo" (source).
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(wholeId)
                .targetClassId(partId)
                .type(RelationshipType.AGGREGATION)
                .owningSide(OwningSide.SOURCE)
                .build();
        ClassEntity whole = minimalClass(wholeId, "Pedido");
        ClassEntity part = minimalClass(partId, "LineaPedido");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(whole, part), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        assertThat(relationshipElement.getAttribute("xmi:type")).isEqualTo("uml:Association");
        assertThat(relationshipElement.getAttribute("memberEnd")).as("Aggregation SI usa memberEnd, es Association").isNotBlank();
        Element sourceEnd = findEndByKind(relationshipElement, "source");
        Element targetEnd = findEndByKind(relationshipElement, "target");
        assertThat(sourceEnd.getAttribute("aggregation")).as("todo (source): none, EA pone el rombo del lado contrario").isEqualTo("none");
        assertThat(targetEnd.getAttribute("aggregation")).as("parte (target): shared, para que EA dibuje el rombo en el todo").isEqualTo("shared");
    }

    @Test
    void exportToXmi_aggregation_wholeAtTarget_getsAggregationSharedOnPartSourceEnd() throws Exception {
        UUID partId = UUID.randomUUID();
        UUID wholeId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        // owningSide TARGET: el "todo" (nuestra notacion) se mueve a target, asi que
        // el "parte" (donde EA espera aggregation="shared") pasa a ser source --
        // confirma que el mapeo invertido se replica en ambas direcciones, no solo
        // el caso default.
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(partId)
                .targetClassId(wholeId)
                .type(RelationshipType.AGGREGATION)
                .owningSide(OwningSide.TARGET)
                .build();
        ClassEntity part = minimalClass(partId, "LineaPedido");
        ClassEntity whole = minimalClass(wholeId, "Pedido");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(part, whole), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        Element sourceEnd = findEndByKind(relationshipElement, "source");
        Element targetEnd = findEndByKind(relationshipElement, "target");
        assertThat(sourceEnd.getAttribute("aggregation")).as("parte (source): shared, para que EA dibuje el rombo en el todo").isEqualTo("shared");
        assertThat(targetEnd.getAttribute("aggregation")).as("todo (target): none, EA pone el rombo del lado contrario").isEqualTo("none");
    }

    @Test
    void exportToXmi_composition_usesCompositeAggregationValue_onPartEnd() throws Exception {
        UUID wholeId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(wholeId)
                .targetClassId(partId)
                .type(RelationshipType.COMPOSITION)
                .owningSide(OwningSide.SOURCE)
                .build();
        ClassEntity whole = minimalClass(wholeId, "Pedido");
        ClassEntity part = minimalClass(partId, "LineaPedido");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(whole, part), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        assertThat(relationshipElement.getAttribute("xmi:type")).isEqualTo("uml:Association");
        assertThat(relationshipElement.getAttribute("memberEnd")).isNotBlank();
        Element sourceEnd = findEndByKind(relationshipElement, "source");
        Element targetEnd = findEndByKind(relationshipElement, "target");
        assertThat(sourceEnd.getAttribute("aggregation")).as("todo (source): none, EA pone el rombo del lado contrario").isEqualTo("none");
        assertThat(targetEnd.getAttribute("aggregation")).as("parte (target): composite, para que EA dibuje el rombo en el todo").isEqualTo("composite");
    }

    @Test
    void exportToXmi_manyToMany_usesUmlAssociationWithMemberEndAndCustomMarker() throws Exception {
        // MANY_TO_MANY no tiene equivalente nativo en UML2 (es convencion de
        // persistencia/ORM) -- se reusa uml:Association + memberEnd (mismo mecanismo
        // ya confirmado que funciona en EA real para ASSOCIATION), marcado con el
        // atributo custom relationshipType para que nuestro propio importador lo
        // distinga de una ASSOCIATION simple.
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .sourceClassId(sourceId)
                .targetClassId(targetId)
                .type(RelationshipType.MANY_TO_MANY)
                .joinTableName("estudiante_curso")
                .build();
        ClassEntity source = minimalClass(sourceId, "Estudiante");
        ClassEntity target = minimalClass(targetId, "Curso");
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(source, target), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");
        Document document = parseDocument(xml);
        Element relationshipElement = findRelationshipElement(document, relationshipId);

        assertThat(relationshipElement.getAttribute("xmi:type")).isEqualTo("uml:Association");
        assertThat(relationshipElement.getAttribute("relationshipType")).isEqualTo("MANY_TO_MANY");
        assertThat(relationshipElement.getAttribute("memberEnd")).isNotBlank();
        assertThat(relationshipElement.getElementsByTagName("ownedEnd").getLength()).isEqualTo(2);
        assertThat(relationshipElement.getAttribute("joinTableName")).isEqualTo("estudiante_curso");
    }

    private static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Element findRelationshipElement(Document document, UUID relationshipId) {
        NodeList packagedElements = document.getElementsByTagName("packagedElement");
        for (int i = 0; i < packagedElements.getLength(); i++) {
            Element element = (Element) packagedElements.item(i);
            if (relationshipId.toString().equals(element.getAttribute("xmi:id"))) {
                return element;
            }
        }
        throw new AssertionError("No se encontro packagedElement con xmi:id " + relationshipId);
    }

    private static Element findClassElementByXmiId(Document document, UUID classId) {
        NodeList packagedElements = document.getElementsByTagName("packagedElement");
        for (int i = 0; i < packagedElements.getLength(); i++) {
            Element element = (Element) packagedElements.item(i);
            if ("uml:Class".equals(element.getAttribute("xmi:type"))
                    && classId.toString().equals(element.getAttribute("xmi:id"))) {
                return element;
            }
        }
        throw new AssertionError("No se encontro packagedElement de clase con xmi:id " + classId);
    }

    private static Element findEndByKind(Element relationshipElement, String kind) {
        NodeList ownedEnds = relationshipElement.getElementsByTagName("ownedEnd");
        for (int i = 0; i < ownedEnds.getLength(); i++) {
            Element end = (Element) ownedEnds.item(i);
            if (kind.equals(end.getAttribute("kind"))) {
                return end;
            }
        }
        throw new AssertionError("No se encontro ownedEnd con kind=" + kind);
    }

    @Test
    void exportToXmi_relationshipEndsReferenceRealXmiIdOfActualClasses_viaDomParse() throws Exception {
        // Test explicito pedido tras el bug real de Enterprise Architect: no basta con
        // que el VALOR del id coincida (eso nunca fue el problema -- este exportador
        // siempre reutilizo relationship.sourceClassId()/targetClassId(), nunca genero
        // un id nuevo al serializar); hay que confirmar que la referencia esta
        // codificada con semantica XMI real (xmi:idref), parseando el XML con un
        // parser DOM de verdad en vez de solo buscar substrings, y resolviendo cada
        // extremo contra el xmi:id real de una clase presente en el MISMO documento.
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
                .build();
        CanonicalModel model = new CanonicalModel("1.0.0", 1, List.of(), List.of(source, target), List.of(relationship));

        String xml = exporter.exportToXmi(model, "Diagrama");

        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document document = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

        // 1. Recolectar el xmi:id REAL de cada clase efectivamente presente en el documento.
        java.util.Set<String> realClassXmiIds = new java.util.HashSet<>();
        org.w3c.dom.NodeList packagedElements = document.getElementsByTagName("packagedElement");
        for (int i = 0; i < packagedElements.getLength(); i++) {
            org.w3c.dom.Element element = (org.w3c.dom.Element) packagedElements.item(i);
            if ("uml:Class".equals(element.getAttribute("xmi:type"))) {
                realClassXmiIds.add(element.getAttribute("xmi:id"));
            }
        }
        assertThat(realClassXmiIds).containsExactlyInAnyOrder(sourceId.toString(), targetId.toString());

        // 2. Ubicar el packagedElement de la relacion (xmi:id == relationshipId) y sus dos ownedEnd.
        org.w3c.dom.Element relationshipElement = null;
        for (int i = 0; i < packagedElements.getLength(); i++) {
            org.w3c.dom.Element element = (org.w3c.dom.Element) packagedElements.item(i);
            if (relationshipId.toString().equals(element.getAttribute("xmi:id"))) {
                relationshipElement = element;
                break;
            }
        }
        assertThat(relationshipElement).as("debe existir un packagedElement con el xmi:id de la relacion").isNotNull();

        org.w3c.dom.NodeList ownedEnds = relationshipElement.getElementsByTagName("ownedEnd");
        assertThat(ownedEnds.getLength()).isEqualTo(2);

        // 3. Por cada ownedEnd, resolver su <type xmi:idref="..."/> y confirmar que
        // referencia EXACTAMENTE el xmi:id de una clase real del mismo documento (paso 1),
        // no un id inventado/regenerado al serializar. De paso, recolectar el xmi:id
        // PROPIO de cada ownedEnd (no el de la clase) para el paso 4.
        java.util.Set<String> referencedClassIds = new java.util.HashSet<>();
        java.util.Set<String> ownedEndOwnIds = new java.util.HashSet<>();
        for (int i = 0; i < ownedEnds.getLength(); i++) {
            org.w3c.dom.Element ownedEnd = (org.w3c.dom.Element) ownedEnds.item(i);
            assertThat(ownedEnd.getAttribute("xmi:type")).isEqualTo("uml:Property");
            String ownEndId = ownedEnd.getAttribute("xmi:id");
            assertThat(ownEndId).as("cada ownedEnd debe tener su propio xmi:id (distinto del de la clase)").isNotBlank();
            ownedEndOwnIds.add(ownEndId);
            org.w3c.dom.NodeList typeChildren = ownedEnd.getElementsByTagName("type");
            assertThat(typeChildren.getLength())
                    .as("cada ownedEnd debe tener exactamente un <type xmi:idref=.../> anidado")
                    .isEqualTo(1);
            org.w3c.dom.Element typeElement = (org.w3c.dom.Element) typeChildren.item(0);
            String idref = typeElement.getAttribute("xmi:idref");
            assertThat(idref).as("el <type> debe usar xmi:idref, no un valor vacio").isNotBlank();
            assertThat(realClassXmiIds)
                    .as("la referencia del extremo debe resolver contra el xmi:id real de una clase "
                            + "presente en el mismo documento, no un id inventado al serializar")
                    .contains(idref);
            referencedClassIds.add(idref);
        }
        assertThat(referencedClassIds).containsExactlyInAnyOrder(sourceId.toString(), targetId.toString());

        // 4. El uml:Association debe declarar memberEnd="idExtremo1 idExtremo2" -- el
        // mecanismo formal de OMG UML2 para decir cuales Properties son los extremos
        // de ESTA asociacion (investigacion del bug de Enterprise Architect: confirmado
        // via el propio estandar OMG que "associations are represented with memberEnd
        // attributes referencing the association ends"). memberEnd apunta al xmi:id
        // del ownedEnd (la Property), NO al xmi:id de la clase.
        String memberEnd = relationshipElement.getAttribute("memberEnd");
        assertThat(memberEnd).as("uml:Association debe declarar memberEnd").isNotBlank();
        java.util.Set<String> memberEndIds = java.util.Set.of(memberEnd.trim().split("\\s+"));
        assertThat(memberEndIds)
                .as("memberEnd debe listar exactamente los xmi:id de los dos ownedEnd, no los de las clases")
                .isEqualTo(ownedEndOwnIds);
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
