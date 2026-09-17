package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Package;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.Waypoint;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * UC15 -- Exportar Diagrama a XMI. Serializa un {@link CanonicalModel} a un
 * documento XML conforme a los namespaces estandar OMG XMI 2.1 / UML 2.1
 * documentados en {@code PLAN_ARQUITECTONICO.md} (seccion "Interoperabilidad
 * OMG XMI 2.1").
 *
 * <p><b>Decision consciente sobre el layout (waypoints):</b> los waypoints de
 * una relacion son un detalle de layout del frontend (React Flow), sin
 * equivalente en el estandar OMG XMI/UML -- ningun elemento {@code uml:*}
 * modela puntos intermedios de un conector visual. En vez de descartarlos, se
 * exportan como elementos {@code <waypoints>/<waypoint>} SIN prefijo de
 * namespace (es decir, fuera de {@code xmi:}/{@code uml:}): una extension no
 * estandar, deliberada y claramente marcada como tal, que un lector XMI
 * estricto (p.ej. Sparx Enterprise Architect) simplemente ignora, pero que
 * permite a una futura reimportacion propia (UC16) restaurar el layout exacto
 * del lienzo. Lo mismo aplica a {@code position} (x/y/width/height) de cada
 * clase.</p>
 *
 * <p>Logica pura (sin dependencias de Spring salvo {@code @Service} para que
 * el controlador la inyecte): usa unicamente {@code javax.xml.parsers}/
 * {@code javax.xml.transform} del JDK estandar, sin agregar una dependencia
 * XML nueva. El {@link DocumentBuilderFactory} se crea sin namespace-awareness
 * (los nombres de elemento con prefijo, p.ej. {@code "uml:Package"}, se tratan
 * como texto opaco); esto evita la ceremonia de {@code createElementNS} +
 * declaracion manual de namespaces por elemento, y el resultado serializado es
 * identico en la practica para un documento que declara los namespaces una
 * sola vez en la raiz.</p>
 */
@Service
public class XmiExporterService {

    public static final String XMI_NAMESPACE = "http://schema.omg.org/spec/XMI/2.1";
    public static final String UML_NAMESPACE = "http://schema.omg.org/spec/UML/2.1";

    /**
     * Exporta {@code model} a un documento XMI 2.1, usando {@code diagramName}
     * como nombre del {@code uml:Model} raiz.
     */
    public String exportToXmi(CanonicalModel model, String diagramName) {
        try {
            Document document = newDocument();

            Element xmiRoot = document.createElement("xmi:XMI");
            xmiRoot.setAttribute("xmi:version", "2.1");
            xmiRoot.setAttribute("xmlns:xmi", XMI_NAMESPACE);
            xmiRoot.setAttribute("xmlns:uml", UML_NAMESPACE);
            document.appendChild(xmiRoot);

            Element umlModel = document.createElement("uml:Model");
            umlModel.setAttribute("xmi:id", "model-" + UUID.randomUUID());
            umlModel.setAttribute("name", diagramName == null ? "" : diagramName);
            xmiRoot.appendChild(umlModel);

            Set<UUID> classIdsInPackages = new HashSet<>();
            for (Package pkg : model.packages()) {
                Element packageElement = document.createElement("uml:Package");
                packageElement.setAttribute("xmi:id", pkg.id().toString());
                packageElement.setAttribute("name", pkg.name());
                umlModel.appendChild(packageElement);

                for (UUID classId : pkg.classIds()) {
                    classIdsInPackages.add(classId);
                    model.findClass(classId).ifPresent(classEntity ->
                            packageElement.appendChild(buildClassElement(document, classEntity)));
                }
            }

            // Clases no referenciadas por ningun paquete: se cuelgan directamente
            // del uml:Model, para que ninguna clase del modelo canonico se pierda
            // en la exportacion.
            for (ClassEntity classEntity : model.classes()) {
                if (!classIdsInPackages.contains(classEntity.id())) {
                    umlModel.appendChild(buildClassElement(document, classEntity));
                }
            }

            for (Relationship relationship : model.relationships()) {
                umlModel.appendChild(buildRelationshipElement(document, relationship));
            }

            return serialize(document);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("No se pudo generar el documento XMI", e);
        }
    }

    private Element buildClassElement(Document document, ClassEntity classEntity) {
        Element classElement = document.createElement("packagedElement");
        classElement.setAttribute("xmi:type", "uml:Class");
        classElement.setAttribute("xmi:id", classEntity.id().toString());
        classElement.setAttribute("name", classEntity.name());
        classElement.setAttribute("visibility", visibilityLiteral(classEntity.visibility()));
        classElement.setAttribute("isAbstract", Boolean.toString(classEntity.isAbstract()));

        Element position = document.createElement("position");
        position.setAttribute("x", Double.toString(classEntity.position().x()));
        position.setAttribute("y", Double.toString(classEntity.position().y()));
        position.setAttribute("width", Double.toString(classEntity.width()));
        position.setAttribute("height", Double.toString(classEntity.height()));
        classElement.appendChild(position);

        for (Attribute attribute : classEntity.attributes()) {
            classElement.appendChild(buildAttributeElement(document, attribute));
        }

        for (Method method : classEntity.methods()) {
            classElement.appendChild(buildOperationElement(document, method));
        }

        return classElement;
    }

    private Element buildAttributeElement(Document document, Attribute attribute) {
        Element attributeElement = document.createElement("ownedAttribute");
        attributeElement.setAttribute("xmi:id", attribute.id().toString());
        attributeElement.setAttribute("name", attribute.name());
        attributeElement.setAttribute("type", attribute.type().name());
        attributeElement.setAttribute("visibility", visibilityLiteral(attribute.visibility()));
        attributeElement.setAttribute("isPrimaryKey", Boolean.toString(attribute.isPrimaryKey()));
        attributeElement.setAttribute("isNullable", Boolean.toString(attribute.isNullable()));
        attributeElement.setAttribute("isUnique", Boolean.toString(attribute.isUnique()));
        attributeElement.setAttribute("length", Integer.toString(attribute.length()));
        attributeElement.setAttribute("precision", Integer.toString(attribute.precision()));
        attributeElement.setAttribute("scale", Integer.toString(attribute.scale()));
        if (attribute.defaultValue() != null) {
            attributeElement.setAttribute("defaultValue", attribute.defaultValue());
        }
        return attributeElement;
    }

    private Element buildOperationElement(Document document, Method method) {
        Element operationElement = document.createElement("ownedOperation");
        operationElement.setAttribute("xmi:id", method.id().toString());
        operationElement.setAttribute("name", method.name());
        operationElement.setAttribute("visibility", visibilityLiteral(method.visibility()));
        if (method.returnType() != null) {
            operationElement.setAttribute("returnType", method.returnType());
        }
        for (Parameter parameter : method.parameters()) {
            Element parameterElement = document.createElement("ownedParameter");
            parameterElement.setAttribute("name", parameter.name());
            parameterElement.setAttribute("type", parameter.type());
            operationElement.appendChild(parameterElement);
        }
        return operationElement;
    }

    private Element buildRelationshipElement(Document document, Relationship relationship) {
        Element relationshipElement = document.createElement("packagedElement");
        relationshipElement.setAttribute("xmi:type", xmiTypeFor(relationship));
        relationshipElement.setAttribute("xmi:id", relationship.id().toString());
        relationshipElement.setAttribute("relationshipType", relationship.type().name());
        relationshipElement.setAttribute("isNavigable", Boolean.toString(relationship.isNavigable()));
        relationshipElement.setAttribute("owningSide", relationship.owningSide().name());
        if (relationship.joinTableName() != null) {
            relationshipElement.setAttribute("joinTableName", relationship.joinTableName());
        }

        relationshipElement.appendChild(buildRelationshipEnd(document, "source",
                relationship.sourceClassId(), relationship.sourceRole(), relationship.sourceMultiplicity()));
        relationshipElement.appendChild(buildRelationshipEnd(document, "target",
                relationship.targetClassId(), relationship.targetRole(), relationship.targetMultiplicity()));

        if (!relationship.waypoints().isEmpty()) {
            Element waypointsElement = document.createElement("waypoints");
            for (Waypoint waypoint : relationship.waypoints()) {
                Element waypointElement = document.createElement("waypoint");
                waypointElement.setAttribute("x", Double.toString(waypoint.x()));
                waypointElement.setAttribute("y", Double.toString(waypoint.y()));
                waypointsElement.appendChild(waypointElement);
            }
            relationshipElement.appendChild(waypointsElement);
        }

        return relationshipElement;
    }

    private Element buildRelationshipEnd(Document document, String kind, UUID classId, String role,
                                          com.modelcollab.metamodel.model.Multiplicity multiplicity) {
        Element endElement = document.createElement("ownedEnd");
        endElement.setAttribute("kind", kind);
        endElement.setAttribute("type", classId.toString());
        if (role != null) {
            endElement.setAttribute("role", role);
        }
        if (multiplicity != null) {
            // Se conserva el atributo "multiplicity" (literal compacta, p.ej.
            // "0..*") por legibilidad/depuracion, y ademas se agregan los
            // elementos lowerValue/upperValue -- la representacion estandar
            // OMG UML 2.x para el limite inferior/superior de un extremo de
            // asociacion -- para que un consumidor XMI estricto (p.ej. Sparx
            // Enterprise Architect en la futura reimportacion de UC16) no
            // dependa de un atributo no estandar.
            endElement.setAttribute("multiplicity", multiplicity.literal());
            String[] bounds = multiplicity.literal().split("\\.\\.", 2);
            endElement.appendChild(buildBoundElement(document, "lowerValue", bounds[0]));
            endElement.appendChild(buildBoundElement(document, "upperValue", bounds[1]));
        }
        return endElement;
    }

    private Element buildBoundElement(Document document, String tagName, String value) {
        Element element = document.createElement(tagName);
        boolean unbounded = "*".equals(value);
        element.setAttribute("xmi:type", unbounded ? "uml:LiteralUnlimitedNatural" : "uml:LiteralInteger");
        element.setAttribute("value", value);
        return element;
    }

    private static String xmiTypeFor(Relationship relationship) {
        return switch (relationship.type()) {
            case GENERALIZATION -> "uml:Generalization";
            case DEPENDENCY -> "uml:Dependency";
            case REALIZATION -> "uml:Realization";
            case ASSOCIATION, AGGREGATION, COMPOSITION, MANY_TO_MANY -> "uml:Association";
        };
    }

    private static String visibilityLiteral(com.modelcollab.metamodel.model.Visibility visibility) {
        return visibility.name().toLowerCase(Locale.ROOT);
    }

    private Document newDocument() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.newDocument();
    }

    private String serialize(Document document) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
