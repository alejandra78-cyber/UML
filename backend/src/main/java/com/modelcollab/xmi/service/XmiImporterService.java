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
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UC16 -- Importar Diagrama desde XMI (contraparte de {@link XmiExporterService},
 * UC15). Parsea un documento XMI/UML a un {@link CanonicalModel}, usando
 * {@code javax.xml.parsers} (DOM) sin namespace-awareness, exactamente el mismo
 * estilo que {@link XmiExporterService} -- los diagramas son chicos, no hace
 * falta SAX ni JAXB.
 *
 * <p><b>Contrato principal verificado:</b> el formato EXACTO que produce
 * {@link XmiExporterService} (ver su javadoc): raíz {@code <xmi:XMI>} →
 * {@code <uml:Model>} → {@code <uml:Package>} con clases dentro (o clases
 * sueltas directo bajo {@code uml:Model}); cada clase es un
 * {@code <packagedElement xmi:type="uml:Class">} con {@code <position>},
 * {@code <ownedAttribute>}, {@code <ownedOperation>}; cada relación es un
 * {@code <packagedElement xmi:type="uml:Association|...">} con dos
 * {@code <ownedEnd>} y {@code <waypoints>/<waypoint>} (extensión propia, sin
 * prefijo de namespace).</p>
 *
 * <p><b>LIMITACIÓN IMPORTANTE, léase antes de una demo con Sparx Enterprise
 * Architect:</b> este parser NO fue validado contra ningún archivo {@code .xmi}
 * real exportado desde Sparx EA -- no existe ninguno en este repositorio ni en
 * el entorno donde se desarrolló, y no hay forma de conseguir uno sin acceso a
 * una instalación real de EA. Se hizo lo más defensivo/tolerante posible a
 * variaciones razonables del dialecto OMG UML/XMI genérico (ver los "fallbacks"
 * documentados método por método más abajo), pero un archivo real de EA podría
 * usar convenciones que este parser no anticipa (p.ej. {@code xmi:idref} en vez
 * de valores embebidos, {@code memberEnd} como referencia externa a un
 * {@code ownedAttribute} en vez de {@code ownedEnd} inline, extensiones
 * {@code <xmi:Extension>} propietarias de Sparx, etc.). Antes de una demo en
 * vivo con un archivo real de EA, probarlo con ese archivo concreto.</p>
 *
 * <p><b>Estrategia de reconciliación de {@code xmi:id}:</b> el esquema canónico
 * exige {@link UUID} para todos los ids, pero el estándar XMI NO exige que
 * {@code xmi:id} sea un UUID (Sparx EA típicamente genera ids como
 * {@code "EAID_61BAB2A9_..."}). Este importador resuelve cada id crudo así: si
 * el texto YA es un UUID válido (como los que produce nuestro propio
 * {@link XmiExporterService}), se usa tal cual -- esto garantiza que un
 * round-trip exportar→importar de un modelo propio preserva los ids exactos.
 * Si no es un UUID válido (dialecto genérico/Sparx), se deriva un UUID
 * determinístico vía {@link UUID#nameUUIDFromBytes(byte[])} sobre el texto
 * crudo, de forma que todas las referencias al mismo id crudo (p.ej. una
 * relación que referencia una clase) resuelvan siempre al mismo UUID dentro del
 * mismo documento.</p>
 *
 * <p><b>Auto-layout:</b> si una clase no trae {@code <position>} (dialecto
 * genérico sin la extensión propia), se le asigna posición vía
 * {@link GridLayoutEngine}, evitando solaparse con las clases que sí traen
 * posición explícita -- exactamente el motor pensado para esto, aunque hasta
 * ahora nadie lo usaba.</p>
 */
@Service
public class XmiImporterService {

    private static final Set<String> RELATIONSHIP_XMI_TYPES = Set.of(
            "uml:Association", "uml:Generalization", "uml:Dependency", "uml:Realization");

    private final GridLayoutEngine gridLayoutEngine;

    public XmiImporterService(GridLayoutEngine gridLayoutEngine) {
        this.gridLayoutEngine = gridLayoutEngine;
    }

    /**
     * Resultado de una importación: el {@link CanonicalModel} parseado más el
     * nombre del diagrama tal como viene en {@code <uml:Model name="...">} (para
     * que el controlador pueda usarlo como nombre por defecto del {@code Diagram}
     * nuevo si el llamador no especifica uno propio).
     */
    public record ImportResult(String diagramName, CanonicalModel model) {
    }

    public ImportResult importXmi(String xmiContent) {
        try (StringReader reader = new StringReader(xmiContent)) {
            return importXmi(new InputSource(reader));
        }
    }

    public ImportResult importXmi(InputStream xmiStream) {
        return importXmi(new InputSource(xmiStream));
    }

    private ImportResult importXmi(InputSource inputSource) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Igual que el exportador: sin namespace-awareness, se tratan "uml:Class"
            // etc. como texto opaco de nombre de elemento/atributo.
            factory.setNamespaceAware(false);
            // Defensivo contra XXE: un archivo XMI importado es contenido externo.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document document = builder.parse(inputSource);
            document.getDocumentElement().normalize();
            return parseDocument(document);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new XmiImportException("No se pudo parsear el documento XMI: " + e.getMessage(), e);
        }
    }

    private ImportResult parseDocument(org.w3c.dom.Document document) {
        Element modelElement = firstElementByAnyTag(document, "uml:Model", "Model");
        if (modelElement == null) {
            throw new XmiImportException(
                    "El documento no contiene un elemento <uml:Model> (ni <Model>) -- no parece un documento XMI/UML valido");
        }
        String diagramName = attr(modelElement, "name");

        // --- 1. Paquetes (name + xmi:id; los classIds se completan mas abajo, una
        // vez que sabemos que clases cuelgan de cada elemento uml:Package). ---
        Map<String, List<UUID>> classIdsByPackageRawId = new LinkedHashMap<>();
        List<PackageDraft> packageDrafts = new ArrayList<>();
        for (Element packageElement : elementsByAnyTag(document, "uml:Package", "Package")) {
            String rawId = requireId(packageElement);
            String name = attr(packageElement, "name");
            packageDrafts.add(new PackageDraft(rawId, name == null ? "" : name));
            classIdsByPackageRawId.put(rawId, new ArrayList<>());
        }

        // --- 2. Clases: se buscan TODAS las packagedElement de tipo uml:Class en
        // todo el documento (esten o no anidadas dentro de un uml:Package), igual
        // que el exportador que las serializa como "sueltas" bajo uml:Model si no
        // tienen paquete. ---
        List<ClassEntity> classesWithPosition = new ArrayList<>();
        List<ClassEntity> classesWithoutPosition = new ArrayList<>();
        List<ClassEntity> orderedClasses = new ArrayList<>();

        for (Element classElement : classElements(document)) {
            ClassEntity classEntity = parseClass(classElement);
            orderedClasses.add(classEntity);
            if (hasExplicitPosition(classElement)) {
                classesWithPosition.add(classEntity);
            } else {
                classesWithoutPosition.add(classEntity);
            }

            Element parentPackage = closestAncestorByAnyTag(classElement, "uml:Package", "Package");
            if (parentPackage != null) {
                String parentRawId = requireId(parentPackage);
                classIdsByPackageRawId.computeIfAbsent(parentRawId, k -> new ArrayList<>()).add(classEntity.id());
            }
        }

        // --- 3. Auto-layout (GridLayoutEngine) para las clases sin <position> explicita. ---
        List<ClassEntity> finalClasses = applyAutoLayout(orderedClasses, classesWithPosition, classesWithoutPosition);

        // --- 4. Paquetes finales, con sus classIds ya resueltos. ---
        List<Package> packages = new ArrayList<>();
        for (PackageDraft draft : packageDrafts) {
            List<UUID> classIds = classIdsByPackageRawId.getOrDefault(draft.rawId(), List.of());
            packages.add(new Package(resolveId(draft.rawId()), draft.name(), classIds));
        }

        // --- 5. Relaciones. ---
        List<Relationship> relationships = new ArrayList<>();
        for (Element relationshipElement : relationshipElements(document)) {
            relationships.add(parseRelationship(relationshipElement));
        }

        CanonicalModel model = new CanonicalModel("1.0.0", 1, packages, finalClasses, relationships);
        return new ImportResult(diagramName, model);
    }

    // ------------------------------------------------------------------
    // Clases
    // ------------------------------------------------------------------

    private List<Element> classElements(org.w3c.dom.Document document) {
        List<Element> result = new ArrayList<>();
        for (Element candidate : elementsByAnyTag(document, "packagedElement", "ownedMember")) {
            String xmiType = firstNonBlank(attr(candidate, "xmi:type"), attr(candidate, "type"));
            if (xmiType != null && xmiType.contains("Class")) {
                result.add(candidate);
            }
        }
        return result;
    }

    private ClassEntity parseClass(Element classElement) {
        String rawId = requireId(classElement);
        String name = firstNonBlank(attr(classElement, "name"), "ClaseSinNombre");
        Visibility visibility = parseVisibility(attr(classElement, "visibility"));
        boolean isAbstract = parseBoolean(attr(classElement, "isAbstract"), false);

        ClassEntity.Builder builder = ClassEntity.builder()
                .id(resolveId(rawId))
                .name(name)
                .visibility(visibility)
                .isAbstract(isAbstract);

        Element positionElement = firstChildByAnyTag(classElement, "position");
        if (positionElement != null) {
            double x = parseDouble(attr(positionElement, "x"), 0);
            double y = parseDouble(attr(positionElement, "y"), 0);
            double width = parseDouble(attr(positionElement, "width"), 240);
            double height = parseDouble(attr(positionElement, "height"), 180);
            builder.position(new Position(x, y)).width(width).height(height);
        } else {
            builder.position(new Position(0, 0));
        }

        List<Attribute> attributes = new ArrayList<>();
        for (Element attributeElement : directChildrenByAnyTag(classElement, "ownedAttribute", "attribute")) {
            attributes.add(parseAttribute(attributeElement));
        }
        builder.attributes(attributes);

        List<Method> methods = new ArrayList<>();
        for (Element operationElement : directChildrenByAnyTag(classElement, "ownedOperation", "operation", "ownedMethod")) {
            methods.add(parseMethod(operationElement));
        }
        builder.methods(methods);

        return builder.build();
    }

    private boolean hasExplicitPosition(Element classElement) {
        return firstChildByAnyTag(classElement, "position") != null;
    }

    private Attribute parseAttribute(Element attributeElement) {
        String rawId = attr(attributeElement, "xmi:id");
        UUID id = rawId != null ? resolveId(rawId) : UUID.randomUUID();
        String name = firstNonBlank(attr(attributeElement, "name"), "atributo");
        AttributeType type = parseAttributeType(attr(attributeElement, "type"));

        Attribute.Builder builder = Attribute.builder()
                .id(id)
                .name(name)
                .type(type)
                .visibility(parseVisibility(attr(attributeElement, "visibility")))
                .primaryKey(parseBoolean(attr(attributeElement, "isPrimaryKey"), false))
                .nullable(parseBoolean(attr(attributeElement, "isNullable"), true))
                .unique(parseBoolean(attr(attributeElement, "isUnique"), false));

        String length = attr(attributeElement, "length");
        if (length != null) {
            builder.length(parseInt(length, 255));
        }
        String precision = attr(attributeElement, "precision");
        if (precision != null) {
            builder.precision(parseInt(precision, 10));
        }
        String scale = attr(attributeElement, "scale");
        if (scale != null) {
            builder.scale(parseInt(scale, 2));
        }
        String defaultValue = attr(attributeElement, "defaultValue");
        if (defaultValue != null) {
            builder.defaultValue(defaultValue);
        }
        return builder.build();
    }

    private Method parseMethod(Element operationElement) {
        String rawId = attr(operationElement, "xmi:id");
        UUID id = rawId != null ? resolveId(rawId) : UUID.randomUUID();
        String name = firstNonBlank(attr(operationElement, "name"), "metodo");
        Visibility visibility = parseVisibility(attr(operationElement, "visibility"));
        String returnType = attr(operationElement, "returnType");

        List<Parameter> parameters = new ArrayList<>();
        for (Element parameterElement : directChildrenByAnyTag(operationElement, "ownedParameter", "parameter")) {
            String paramName = firstNonBlank(attr(parameterElement, "name"), "param");
            String paramType = firstNonBlank(attr(parameterElement, "type"), "String");
            parameters.add(new Parameter(paramName, paramType));
        }

        return new Method(id, name, returnType, visibility, parameters);
    }

    // ------------------------------------------------------------------
    // Relaciones
    // ------------------------------------------------------------------

    private List<Element> relationshipElements(org.w3c.dom.Document document) {
        List<Element> result = new ArrayList<>();
        for (Element candidate : elementsByAnyTag(document, "packagedElement", "ownedMember")) {
            String xmiType = firstNonBlank(attr(candidate, "xmi:type"), attr(candidate, "type"));
            if (xmiType != null && RELATIONSHIP_XMI_TYPES.contains(xmiType)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private Relationship parseRelationship(Element relationshipElement) {
        String rawId = requireId(relationshipElement);
        String xmiType = firstNonBlank(attr(relationshipElement, "xmi:type"), attr(relationshipElement, "type"));

        List<Element> ends = directChildrenByAnyTag(relationshipElement, "ownedEnd", "memberEnd");
        Element sourceEnd = findEndByKind(ends, "source", 0);
        Element targetEnd = findEndByKind(ends, "target", 1);

        RelationshipType type = resolveRelationshipType(relationshipElement, xmiType, sourceEnd, targetEnd);

        UUID sourceClassId = sourceEnd != null ? resolveEndClassId(sourceEnd) : UUID.randomUUID();
        UUID targetClassId = targetEnd != null ? resolveEndClassId(targetEnd) : UUID.randomUUID();

        OwningSide owningSide = parseOwningSide(attr(relationshipElement, "owningSide"));
        boolean isNavigable = parseBoolean(attr(relationshipElement, "isNavigable"), true);
        String joinTableName = attr(relationshipElement, "joinTableName");

        Relationship.Builder builder = Relationship.builder()
                .id(resolveId(rawId))
                .sourceClassId(sourceClassId)
                .targetClassId(targetClassId)
                .type(type)
                .owningSide(owningSide)
                .isNavigable(isNavigable)
                .sourceRole(sourceEnd != null ? attr(sourceEnd, "role") : null)
                .targetRole(targetEnd != null ? attr(targetEnd, "role") : null)
                .sourceMultiplicity(sourceEnd != null ? resolveMultiplicity(sourceEnd, type) : null)
                .targetMultiplicity(targetEnd != null ? resolveMultiplicity(targetEnd, type) : null);
        if (joinTableName != null) {
            builder.joinTableName(joinTableName);
        }

        Element waypointsElement = firstChildByAnyTag(relationshipElement, "waypoints");
        if (waypointsElement != null) {
            List<Waypoint> waypoints = new ArrayList<>();
            for (Element waypointElement : directChildrenByAnyTag(waypointsElement, "waypoint")) {
                double x = parseDouble(attr(waypointElement, "x"), 0);
                double y = parseDouble(attr(waypointElement, "y"), 0);
                waypoints.add(new Waypoint(x, y));
            }
            builder.waypoints(waypoints);
        }

        return builder.build();
    }

    private static Element findEndByKind(List<Element> ends, String kind, int fallbackIndex) {
        for (Element end : ends) {
            if (kind.equalsIgnoreCase(attr(end, "kind"))) {
                return end;
            }
        }
        // Dialecto generico sin atributo "kind": se asume orden documental
        // (primer ownedEnd = source, segundo = target) -- fallback documentado.
        return ends.size() > fallbackIndex ? ends.get(fallbackIndex) : null;
    }

    private UUID resolveEndClassId(Element endElement) {
        String typeRef = attr(endElement, "type");
        if (typeRef == null || typeRef.isBlank()) {
            return UUID.randomUUID();
        }
        return resolveId(typeRef);
    }

    private RelationshipType resolveRelationshipType(Element relationshipElement, String xmiType,
                                                       Element sourceEnd, Element targetEnd) {
        // Camino principal: nuestro propio exportador (UC15) escribe el atributo
        // custom "relationshipType" con el literal exacto del enum -- round-trip
        // perfecto sin ninguna inferencia.
        String relationshipTypeAttr = attr(relationshipElement, "relationshipType");
        if (relationshipTypeAttr != null) {
            try {
                return RelationshipType.valueOf(relationshipTypeAttr);
            } catch (IllegalArgumentException ignored) {
                // cae al camino generico de abajo
            }
        }

        // Camino generico (dialecto OMG UML/Sparx sin el atributo custom): se
        // infiere desde xmi:type + el atributo estandar "aggregation" en los
        // extremos ("composite"/"shared") para distinguir Association/Aggregation/Composition.
        if (xmiType == null) {
            return RelationshipType.ASSOCIATION;
        }
        return switch (xmiType) {
            case "uml:Generalization" -> RelationshipType.GENERALIZATION;
            case "uml:Dependency" -> RelationshipType.DEPENDENCY;
            case "uml:Realization", "uml:InterfaceRealization" -> RelationshipType.REALIZATION;
            default -> {
                String sourceAggregation = sourceEnd != null ? attr(sourceEnd, "aggregation") : null;
                String targetAggregation = targetEnd != null ? attr(targetEnd, "aggregation") : null;
                if ("composite".equalsIgnoreCase(sourceAggregation) || "composite".equalsIgnoreCase(targetAggregation)) {
                    yield RelationshipType.COMPOSITION;
                }
                if ("shared".equalsIgnoreCase(sourceAggregation) || "shared".equalsIgnoreCase(targetAggregation)) {
                    yield RelationshipType.AGGREGATION;
                }
                yield RelationshipType.ASSOCIATION;
            }
        };
    }

    /**
     * GENERALIZATION/DEPENDENCY/REALIZATION no llevan multiplicidad en UML (ver
     * javadoc de {@link RelationshipType}): si un extremo de esos tipos no trae
     * ningun dato de multiplicidad, se preserva {@code null} en vez de aplicar el
     * fallback "1..1" de mejor esfuerzo, que solo tiene sentido para relaciones
     * estructurales (ASSOCIATION/AGGREGATION/COMPOSITION/MANY_TO_MANY).
     */
    private Multiplicity resolveMultiplicity(Element endElement, RelationshipType relationshipType) {
        String literal = attr(endElement, "multiplicity");
        if (literal != null) {
            try {
                return Multiplicity.fromLiteral(literal);
            } catch (IllegalArgumentException ignored) {
                // cae al camino de lowerValue/upperValue
            }
        }

        Element lowerValueElement = firstChildByAnyTag(endElement, "lowerValue");
        Element upperValueElement = firstChildByAnyTag(endElement, "upperValue");
        if (lowerValueElement == null && upperValueElement == null) {
            if (relationshipType == RelationshipType.GENERALIZATION
                    || relationshipType == RelationshipType.DEPENDENCY
                    || relationshipType == RelationshipType.REALIZATION) {
                return null;
            }
            // Dialecto generico sin ninguna informacion de multiplicidad: se asume
            // 1..1 (fallback documentado, mejor esfuerzo).
            return Multiplicity.ONE_ONE;
        }
        String lower = lowerValueElement != null ? attr(lowerValueElement, "value") : "1";
        String upper = upperValueElement != null ? attr(upperValueElement, "value") : "1";
        String derivedLiteral = (lower == null ? "1" : lower) + ".." + (upper == null ? "1" : upper);
        try {
            return Multiplicity.fromLiteral(derivedLiteral);
        } catch (IllegalArgumentException ignored) {
            return Multiplicity.ONE_ONE;
        }
    }

    private static OwningSide parseOwningSide(String value) {
        if (value == null) {
            return OwningSide.SOURCE;
        }
        try {
            return OwningSide.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return OwningSide.SOURCE;
        }
    }

    // ------------------------------------------------------------------
    // Auto-layout
    // ------------------------------------------------------------------

    private List<ClassEntity> applyAutoLayout(List<ClassEntity> orderedClasses,
                                               List<ClassEntity> classesWithPosition,
                                               List<ClassEntity> classesWithoutPosition) {
        if (classesWithoutPosition.isEmpty()) {
            return orderedClasses;
        }
        List<Position> computedPositions =
                gridLayoutEngine.computePositions(classesWithPosition, classesWithoutPosition.size());

        Map<UUID, Position> positionById = new LinkedHashMap<>();
        for (int i = 0; i < classesWithoutPosition.size(); i++) {
            positionById.put(classesWithoutPosition.get(i).id(), computedPositions.get(i));
        }

        List<ClassEntity> result = new ArrayList<>(orderedClasses.size());
        for (ClassEntity classEntity : orderedClasses) {
            Position autoPosition = positionById.get(classEntity.id());
            if (autoPosition == null) {
                result.add(classEntity);
            } else {
                result.add(ClassEntity.builder()
                        .id(classEntity.id())
                        .name(classEntity.name())
                        .visibility(classEntity.visibility())
                        .isAbstract(classEntity.isAbstract())
                        .position(autoPosition)
                        .width(classEntity.width())
                        .height(classEntity.height())
                        .attributes(classEntity.attributes())
                        .methods(classEntity.methods())
                        .build());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Id resolution (ver javadoc de la clase: "Estrategia de reconciliacion de xmi:id")
    // ------------------------------------------------------------------

    private static UUID resolveId(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String requireId(Element element) {
        String id = firstNonBlank(attr(element, "xmi:id"), attr(element, "id"));
        if (id == null) {
            throw new XmiImportException("Elemento <" + element.getTagName() + "> sin xmi:id/id: no se puede importar");
        }
        return id;
    }

    private record PackageDraft(String rawId, String name) {
    }

    // ------------------------------------------------------------------
    // Utilidades de parseo defensivo (valores ausentes -> defaults documentados
    // arriba, nunca una excepcion por un atributo opcional faltante).
    // ------------------------------------------------------------------

    private static String attr(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private static Visibility parseVisibility(String value) {
        if (value == null || value.isBlank()) {
            return Visibility.PUBLIC;
        }
        try {
            return Visibility.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Visibility.PUBLIC;
        }
    }

    private static AttributeType parseAttributeType(String value) {
        if (value == null || value.isBlank()) {
            return AttributeType.VARCHAR;
        }
        try {
            return AttributeType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Dialecto generico con tipos de datos UML/PrimitiveType (p.ej. "String",
            // "int", "boolean", "DateTime"): mejor esfuerzo de mapeo a nuestro catalogo.
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.contains("date") && normalized.contains("time")) {
                return AttributeType.DATETIME;
            }
            if (normalized.contains("date")) {
                return AttributeType.DATE;
            }
            if (normalized.contains("bool")) {
                return AttributeType.BOOLEAN;
            }
            if (normalized.contains("decimal") || normalized.contains("double") || normalized.contains("float")) {
                return AttributeType.DECIMAL;
            }
            if (normalized.contains("long") || normalized.equals("bigint")) {
                return AttributeType.BIGINT;
            }
            if (normalized.contains("int")) {
                return AttributeType.INTEGER;
            }
            if (normalized.contains("uuid") || normalized.contains("guid")) {
                return AttributeType.UUID;
            }
            if (normalized.contains("text") || normalized.contains("clob")) {
                return AttributeType.TEXT;
            }
            return AttributeType.VARCHAR;
        }
    }

    // ------------------------------------------------------------------
    // Navegacion DOM defensiva: acepta el nombre de tag "canonico" (el que usa
    // nuestro propio exportador) y uno o mas alias genericos.
    // ------------------------------------------------------------------

    private static Element firstElementByAnyTag(org.w3c.dom.Document document, String... tagNames) {
        for (String tagName : tagNames) {
            NodeList nodes = document.getElementsByTagName(tagName);
            if (nodes.getLength() > 0) {
                return (Element) nodes.item(0);
            }
        }
        return null;
    }

    private static List<Element> elementsByAnyTag(org.w3c.dom.Document document, String... tagNames) {
        Set<Element> seen = new LinkedHashSet<>();
        List<Element> result = new ArrayList<>();
        for (String tagName : tagNames) {
            NodeList nodes = document.getElementsByTagName(tagName);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if (seen.add(element)) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static Element firstChildByAnyTag(Element parent, String... tagNames) {
        List<Element> children = directChildrenByAnyTag(parent, tagNames);
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> directChildrenByAnyTag(Element parent, String... tagNames) {
        Set<String> wanted = new HashSet<>(List.of(tagNames));
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node instanceof Element element && wanted.contains(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element closestAncestorByAnyTag(Element element, String... tagNames) {
        Set<String> wanted = new HashSet<>(List.of(tagNames));
        org.w3c.dom.Node current = element.getParentNode();
        while (current instanceof Element currentElement) {
            if (wanted.contains(currentElement.getTagName())) {
                return currentElement;
            }
            current = current.getParentNode();
        }
        return null;
    }

    /** Excepción no chequeada para cualquier fallo de parseo del XMI de entrada. */
    public static class XmiImportException extends RuntimeException {
        public XmiImportException(String message) {
            super(message);
        }

        public XmiImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
