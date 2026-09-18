package com.modelcollab.xmi.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Package;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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

    /** Nombre corto y generico para {@code uml:Model/@name} -- ver javadoc de {@link #exportToXmi}. */
    private static final String GENERIC_MODEL_NAME = "Model";

    /**
     * Exporta {@code model} a un documento XMI 2.1. {@code diagramName} NO se usa
     * como {@code uml:Model/@name} (ajuste de UX pedido tras probar en Enterprise
     * Architect real): EA usa ese atributo como prefijo de namespace visible en
     * cada clase importada (p.ej. {@code "Diagrama de prueba::Component"}), asi
     * que un nombre de diagrama largo ensucia la vista. En su lugar,
     * {@code uml:Model/@name} queda fijo en {@value #GENERIC_MODEL_NAME}, y el
     * nombre real del diagrama se preserva igual en el atributo custom
     * {@code diagramName} (no estandar, ignorado por EA, leido de vuelta por
     * {@link XmiImporterService} con prioridad sobre {@code name} en un
     * reimport propio).
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
            umlModel.setAttribute("name", GENERIC_MODEL_NAME);
            umlModel.setAttribute("diagramName", diagramName == null ? "" : diagramName);
            xmiRoot.appendChild(umlModel);

            // xmi:id de cada clase -> su Element ya insertado en el documento. Necesario
            // para GENERALIZATION (ver mas abajo): a diferencia del resto de los tipos,
            // no se serializa como packagedElement propio, sino como elemento anidado
            // DENTRO de la clase hija -- hace falta poder ubicar ese Element de vuelta
            // al procesar las relaciones, que se recorren en una pasada aparte.
            Map<UUID, Element> classElementsById = new HashMap<>();

            Set<UUID> classIdsInPackages = new HashSet<>();
            for (Package pkg : model.packages()) {
                Element packageElement = document.createElement("uml:Package");
                packageElement.setAttribute("xmi:id", pkg.id().toString());
                packageElement.setAttribute("name", pkg.name());
                umlModel.appendChild(packageElement);

                for (UUID classId : pkg.classIds()) {
                    classIdsInPackages.add(classId);
                    model.findClass(classId).ifPresent(classEntity -> {
                        Element classElement = buildClassElement(document, classEntity);
                        packageElement.appendChild(classElement);
                        classElementsById.put(classEntity.id(), classElement);
                    });
                }
            }

            // Clases no referenciadas por ningun paquete: se cuelgan directamente
            // del uml:Model, para que ninguna clase del modelo canonico se pierda
            // en la exportacion.
            for (ClassEntity classEntity : model.classes()) {
                if (!classIdsInPackages.contains(classEntity.id())) {
                    Element classElement = buildClassElement(document, classEntity);
                    umlModel.appendChild(classElement);
                    classElementsById.put(classEntity.id(), classElement);
                }
            }

            // Bug real confirmado contra Enterprise Architect real (v15): NINGUNA
            // Generalization conectaba, incluso con xmi:id + general/specific como
            // atributos planos sobre un packagedElement propio de xmi:type
            // uml:Generalization (intento anterior, descartado). Investigacion mas a
            // fondo (confirmada contra un archivo XMI real y publico,
            // github.com/STIXProject/specifications, ver javadoc de
            // buildGeneralizationElement): en UML2, Generalization NO es una relacion
            // de nivel superior como Association/Dependency -- es una propiedad de la
            // clase ESPECIFICA (Classifier::generalization), y se serializa como un
            // elemento <generalization> ANIDADO dentro del packagedElement de esa
            // clase, con SOLO xmi:id + general (el atributo "specific" no existe en el
            // estandar: es implicito, la clase que lo contiene). Por eso GENERALIZATION
            // se procesa aparte, ANTES del resto, y nunca llega a
            // buildRelationshipElement/se cuelga de umlModel.
            for (Relationship relationship : model.relationships()) {
                if (relationship.type() == RelationshipType.GENERALIZATION) {
                    Element specificClassElement = classElementsById.get(relationship.sourceClassId());
                    if (specificClassElement != null) {
                        specificClassElement.appendChild(buildGeneralizationElement(document, relationship));
                        continue;
                    }
                    // Defensivo: no deberia pasar (MetamodelValidator ya rechaza
                    // relaciones huerfanas antes de llegar aqui), pero si el
                    // sourceClassId no resuelve a ninguna clase del propio modelo,
                    // se cae al formato de packagedElement de nivel superior en vez
                    // de perder silenciosamente la relacion.
                }
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
        String xmiType = xmiTypeFor(relationship);
        relationshipElement.setAttribute("xmi:type", xmiType);
        relationshipElement.setAttribute("xmi:id", relationship.id().toString());
        relationshipElement.setAttribute("relationshipType", relationship.type().name());
        relationshipElement.setAttribute("isNavigable", Boolean.toString(relationship.isNavigable()));
        relationshipElement.setAttribute("owningSide", relationship.owningSide().name());
        if (relationship.joinTableName() != null) {
            relationshipElement.setAttribute("joinTableName", relationship.joinTableName());
        }

        // Bug real confirmado contra Enterprise Architect real (v15): GENERALIZATION
        // y DEPENDENCY/REALIZATION no conectaban EN ABSOLUTO cuando se serializaban
        // con ownedEnd (el mecanismo de ASSOCIATION) -- en UML2 estos tipos no tienen
        // "extremos" con multiplicidad, se resuelven con atributos DIRECTOS por idref
        // sobre el propio packagedElement (general/specific para Generalization,
        // client/supplier para Dependency). REALIZATION no estaba en la lista
        // explicita de 6 tipos del pedido, pero es una subclase de Dependency en UML2
        // y sufre el mismo problema estructural -- se corrige igual, en linea con
        // "TODOS los tipos" (frase inicial del pedido). ASSOCIATION/AGGREGATION/
        // COMPOSITION/MANY_TO_MANY si usan ownedEnd+memberEnd (uml:Association, ya
        // confirmado que funciona para ASSOCIATION en EA real).
        switch (relationship.type()) {
            case GENERALIZATION -> {
                // Convencion ya establecida en GeneratorModelBuilder (generador de
                // backend): sourceClassId = clase hija, targetClassId = clase padre.
                relationshipElement.setAttribute("general", relationship.targetClassId().toString());
                relationshipElement.setAttribute("specific", relationship.sourceClassId().toString());
            }
            case DEPENDENCY, REALIZATION -> {
                relationshipElement.setAttribute("client", relationship.sourceClassId().toString());
                relationshipElement.setAttribute("supplier", relationship.targetClassId().toString());
            }
            default -> buildAssociationEnds(document, relationshipElement, relationship);
        }

        appendWaypoints(document, relationshipElement, relationship.waypoints());

        return relationshipElement;
    }

    /**
     * UML2: {@code Generalization} no es una relacion de nivel superior -- es una
     * propiedad de la clase especifica ({@code Classifier::generalization}), y se
     * serializa como un elemento {@code <generalization>} ANIDADO dentro del
     * {@code packagedElement} de esa clase (ver el javadoc de la llamada en
     * {@link #exportToXmi} para la evidencia: confirmado contra un archivo XMI
     * real y publico, {@code BasicTypes.uml.xmi} del repositorio
     * {@code github.com/STIXProject/specifications}), con SOLO {@code xmi:id} +
     * {@code general} -- el atributo {@code specific} NO existe en el estandar:
     * es implicito, la clase que contiene el elemento. Sin {@code xmi:type}
     * propio tampoco (el ejemplo real no lo lleva; el tipo ya esta implicito en
     * el nombre del elemento {@code <generalization>} en si).
     */
    private Element buildGeneralizationElement(Document document, Relationship relationship) {
        Element generalizationElement = document.createElement("generalization");
        generalizationElement.setAttribute("xmi:id", relationship.id().toString());
        generalizationElement.setAttribute("general", relationship.targetClassId().toString());
        // Marca custom (ignorada por EA) para que nuestro propio importador confirme
        // el tipo sin depender de inferencia -- aunque la nueva estructura anidada ya
        // es inequivoca por si sola.
        generalizationElement.setAttribute("relationshipType", RelationshipType.GENERALIZATION.name());
        appendWaypoints(document, generalizationElement, relationship.waypoints());
        return generalizationElement;
    }

    private void appendWaypoints(Document document, Element parent, java.util.List<Waypoint> waypoints) {
        if (waypoints.isEmpty()) {
            return;
        }
        Element waypointsElement = document.createElement("waypoints");
        for (Waypoint waypoint : waypoints) {
            Element waypointElement = document.createElement("waypoint");
            waypointElement.setAttribute("x", Double.toString(waypoint.x()));
            waypointElement.setAttribute("y", Double.toString(waypoint.y()));
            waypointsElement.appendChild(waypointElement);
        }
        parent.appendChild(waypointsElement);
    }

    /**
     * ASSOCIATION, AGGREGATION, COMPOSITION y MANY_TO_MANY se serializan todas como
     * {@code uml:Association} con dos {@code ownedEnd} + {@code memberEnd} (ya
     * confirmado que ASSOCIATION conecta bien en Enterprise Architect real con este
     * mecanismo). MANY_TO_MANY no tiene equivalente nativo en UML2 -- es una
     * convencion de persistencia/ORM, no de notacion UML -- asi que se reusa el mismo
     * {@code uml:Association} y se apoya en el atributo custom {@code relationshipType}
     * (ya emitido siempre, ver arriba) para que nuestro propio importador la
     * reconozca; EA no va a mostrar un simbolo nativo de "muchos a muchos" (no
     * existe en su paleta), pero al menos va a dibujar la linea de asociacion.
     */
    private void buildAssociationEnds(Document document, Element relationshipElement, Relationship relationship) {
        String sourceAggregation = null;
        String targetAggregation = null;
        if (relationship.type() == RelationshipType.AGGREGATION || relationship.type() == RelationshipType.COMPOSITION) {
            String wholeValue = relationship.type() == RelationshipType.AGGREGATION ? "shared" : "composite";
            // Misma convencion que ya usa el frontend shippeado (UmlRelationshipEdge.wholeEnd,
            // web/src/features/modelado-manual/UmlRelationshipEdge.tsx): el extremo "todo"
            // (el que lleva el rombo en NUESTRA notacion) es target solo si owningSide ==
            // TARGET, source en cualquier otro caso (incluido null).
            boolean wholeIsTarget = relationship.owningSide() == OwningSide.TARGET;
            // OJO -- particularidad CONFIRMADA de Sparx Enterprise Architect, contraria a
            // la lectura "intuitiva" del nombre del atributo: EA dibuja el rombo del lado
            // OPUESTO al ownedEnd marcado con aggregation="shared"/"composite". O sea que
            // ese valor hay que escribirlo en el extremo "parte" (el que en nuestro propio
            // modelo NO lleva el rombo), y "none" en el extremo "todo" (el que si lo lleva
            // en nuestra notacion). Confirmado exportando un diagrama real e importandolo
            // en EA: con el mapeo "intuitivo" (shared/composite en el "todo") el rombo
            // aparecia invertido, en el extremo "parte". NO revertir este mapeo pensando
            // que es un error -- es deliberado y especifico de como EA interpreta XMI.
            sourceAggregation = wholeIsTarget ? wholeValue : "none";
            targetAggregation = wholeIsTarget ? "none" : wholeValue;
        }

        String sourceEndId = UUID.randomUUID().toString();
        String targetEndId = UUID.randomUUID().toString();
        relationshipElement.appendChild(buildRelationshipEnd(document, sourceEndId, "source",
                relationship.sourceClassId(), relationship.sourceRole(), relationship.sourceMultiplicity(), sourceAggregation));
        relationshipElement.appendChild(buildRelationshipEnd(document, targetEndId, "target",
                relationship.targetClassId(), relationship.targetRole(), relationship.targetMultiplicity(), targetAggregation));
        // Segun el estandar OMG UML2 (confirmado via busqueda): "In XMI, associations
        // are represented with memberEnd attributes referencing the association ends"
        // -- ownedEnd por si solo declara Properties poseidas por la asociacion, pero
        // memberEnd es el mecanismo formal que dice CUALES de esas Properties son
        // efectivamente los dos extremos de ESTA asociacion. Ya confirmado en EA real
        // que esto hace que ASSOCIATION conecte correctamente.
        relationshipElement.setAttribute("memberEnd", sourceEndId + " " + targetEndId);
    }

    private Element buildRelationshipEnd(Document document, String endId, String kind, UUID classId, String role,
                                          com.modelcollab.metamodel.model.Multiplicity multiplicity, String aggregation) {
        Element endElement = document.createElement("ownedEnd");
        // xmi:type="uml:Property" + <type xmi:idref="..."/> anidado: bug real
        // encontrado con Enterprise Architect real (no round-trip propio) --
        // un atributo plano type="<uuid>" (version anterior) NO es una referencia
        // XMI valida (le falta xmi:idref, que es como el estandar OMG XMI 2.x
        // marca que el valor de un atributo debe resolverse contra el xmi:id de
        // otro elemento del documento). El valor del id en si SIEMPRE fue el
        // correcto (relationship.sourceClassId()/targetClassId(), nunca uno
        // generado de nuevo al serializar) -- el problema era exclusivamente la
        // falta de semantica xmi:idref en la referencia.
        //
        // xmi:id propio (endId): necesario para que memberEnd del uml:Association
        // padre pueda referenciar este extremo especifico -- memberEnd apunta al
        // Property/ownedEnd, NO a la clase.
        endElement.setAttribute("xmi:type", "uml:Property");
        endElement.setAttribute("xmi:id", endId);
        endElement.setAttribute("kind", kind);
        if (aggregation != null) {
            // AGGREGATION/COMPOSITION: atributo estandar OMG UML2 que le dice a un
            // lector XMI (EA incluido) en cual extremo va el rombo -- "shared" para
            // agregacion, "composite" para composicion, "none" en el extremo opuesto
            // (el "todo"/"parte" respectivamente). Sin esto la linea conectaba pero
            // sin el simbolo del rombo (reportado con EA real).
            endElement.setAttribute("aggregation", aggregation);
        }
        Element typeElement = document.createElement("type");
        typeElement.setAttribute("xmi:idref", classId.toString());
        endElement.appendChild(typeElement);
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
