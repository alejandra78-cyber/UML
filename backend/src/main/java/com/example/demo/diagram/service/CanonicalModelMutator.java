package com.example.demo.diagram.service;

import com.example.demo.collaboration.dto.OperationType;
import com.example.demo.metamodel.model.Attribute;
import com.example.demo.metamodel.model.AttributeType;
import com.example.demo.metamodel.model.CanonicalModel;
import com.example.demo.metamodel.model.ClassEntity;
import com.example.demo.metamodel.model.Method;
import com.example.demo.metamodel.model.Multiplicity;
import com.example.demo.metamodel.model.OwningSide;
import com.example.demo.metamodel.model.Package;
import com.example.demo.metamodel.model.Parameter;
import com.example.demo.metamodel.model.Position;
import com.example.demo.metamodel.model.Relationship;
import com.example.demo.metamodel.model.RelationshipType;
import com.example.demo.metamodel.model.Visibility;
import com.example.demo.metamodel.model.Waypoint;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aplica cada {@link OperationType} del catalogo (seccion 8.1 del documento de
 * arquitectura) sobre un {@link CanonicalModel} en memoria, produciendo un
 * nuevo modelo inmutable. No conoce Spring Data ni PostgreSQL: es logica pura
 * y facil de testear; la persistencia real vive en {@link DiagramMutationService}.
 *
 * <p>Convencion de payload (no fijada explicitamente por el documento, inferida
 * de la columna "Descripcion de Campos Mutables" de la seccion 8.1):</p>
 * <ul>
 *   <li>Operaciones {@code ADD_*}: el payload es el objeto nuevo completo,
 *   serializado segun el esquema canonico (seccion 7).</li>
 *   <li>Operaciones {@code UPDATE_*}/{@code RENAME_*}/{@code RESIZE_*}: el
 *   payload contiene SOLO los campos que cambian (merge parcial sobre el
 *   elemento existente); el resto se preserva sin tocar. Esto es explicito en
 *   el documento para {@code UPDATE_RELATIONSHIP} ("sin destruir y recrear el
 *   conector") y se generaliza aqui al resto de los UPDATE.</li>
 *   <li>Operaciones {@code DELETE_*}: no usan payload, solo {@code targetId}.</li>
 * </ul>
 */
@Component
public class CanonicalModelMutator {

    private final ObjectMapper objectMapper;

    public CanonicalModelMutator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MutationOutcome apply(CanonicalModel model, OperationType type, UUID targetId, Map<String, Object> payload) {
        try {
            return switch (type) {
                case ADD_CLASS -> addClass(model, payload);
                case MOVE_CLASS -> moveClass(model, targetId, payload);
                case RENAME_CLASS -> renameClass(model, targetId, payload);
                case DELETE_CLASS -> deleteClass(model, targetId);
                case ADD_ATTRIBUTE -> addAttribute(model, targetId, payload);
                case UPDATE_ATTRIBUTE -> updateAttribute(model, targetId, payload);
                case DELETE_ATTRIBUTE -> deleteAttribute(model, targetId);
                case ADD_RELATIONSHIP -> addRelationship(model, payload);
                case UPDATE_WAYPOINTS -> updateWaypoints(model, targetId, payload);
                case DELETE_RELATIONSHIP -> deleteRelationship(model, targetId);
                case UPDATE_RELATIONSHIP -> updateRelationship(model, targetId, payload);
                case RESIZE_CLASS -> resizeClass(model, targetId, payload);
                case ADD_METHOD -> addMethod(model, targetId, payload);
                case UPDATE_METHOD -> updateMethod(model, targetId, payload);
                case DELETE_METHOD -> deleteMethod(model, targetId);
                case ADD_PACKAGE -> addPackage(model, payload);
                case UPDATE_PACKAGE -> updatePackage(model, targetId, payload);
                case DELETE_PACKAGE -> deletePackage(model, targetId);
                case BULK_MERGE -> bulkMerge(model, payload);
                default -> MutationOutcome.rejected(
                        "La operacion " + type + " no se aplica sobre el CanonicalModel via /mutate");
            };
        } catch (RuntimeException ex) {
            return MutationOutcome.rejected("Payload invalido para " + type + ": " + ex.getMessage());
        }
    }

    // ---- ADD ----

    private MutationOutcome addClass(CanonicalModel model, Map<String, Object> payload) {
        ClassEntity newClass = objectMapper.convertValue(payload, ClassEntity.class);
        if (model.findClass(newClass.id()).isPresent()) {
            return MutationOutcome.rejected("Ya existe una clase con id " + newClass.id());
        }
        return MutationOutcome.applied(withClasses(model, append(model.classes(), newClass)));
    }

    private MutationOutcome addAttribute(CanonicalModel model, UUID classId, Map<String, Object> payload) {
        Optional<ClassEntity> found = model.findClass(classId);
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Clase no encontrada: " + classId);
        }
        Attribute newAttribute = objectMapper.convertValue(payload, Attribute.class);
        ClassEntity clazz = found.get();
        if (clazz.attributes().stream().anyMatch(a -> a.id().equals(newAttribute.id()))) {
            return MutationOutcome.rejected("Ya existe un atributo con id " + newAttribute.id());
        }
        ClassEntity updated = withAttributes(clazz, append(clazz.attributes(), newAttribute));
        return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), classId, updated)));
    }

    private MutationOutcome addMethod(CanonicalModel model, UUID classId, Map<String, Object> payload) {
        Optional<ClassEntity> found = model.findClass(classId);
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Clase no encontrada: " + classId);
        }
        Method newMethod = objectMapper.convertValue(payload, Method.class);
        ClassEntity clazz = found.get();
        if (clazz.methods().stream().anyMatch(m -> m.id().equals(newMethod.id()))) {
            return MutationOutcome.rejected("Ya existe un metodo con id " + newMethod.id());
        }
        ClassEntity updated = withMethods(clazz, append(clazz.methods(), newMethod));
        return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), classId, updated)));
    }

    private MutationOutcome addRelationship(CanonicalModel model, Map<String, Object> payload) {
        Relationship newRelationship = objectMapper.convertValue(payload, Relationship.class);
        if (findRelationship(model, newRelationship.id()).isPresent()) {
            return MutationOutcome.rejected("Ya existe una relacion con id " + newRelationship.id());
        }
        return MutationOutcome.applied(withRelationships(model, append(model.relationships(), newRelationship)));
    }

    private MutationOutcome addPackage(CanonicalModel model, Map<String, Object> payload) {
        Package newPackage = objectMapper.convertValue(payload, Package.class);
        if (model.packages().stream().anyMatch(p -> p.id().equals(newPackage.id()))) {
            return MutationOutcome.rejected("Ya existe un paquete con id " + newPackage.id());
        }
        return MutationOutcome.applied(withPackages(model, append(model.packages(), newPackage)));
    }

    // ---- MOVE / RESIZE / RENAME ----

    private MutationOutcome moveClass(CanonicalModel model, UUID classId, Map<String, Object> payload) {
        return updateClass(model, classId, clazz -> {
            Double x = doubleField(payload, "x");
            Double y = doubleField(payload, "y");
            if (x == null || y == null) {
                throw new IllegalArgumentException("MOVE_CLASS requiere x e y");
            }
            return new ClassEntity(clazz.id(), clazz.name(), clazz.visibility(), clazz.isAbstract(),
                    new Position(x, y), clazz.width(), clazz.height(), clazz.attributes(), clazz.methods());
        });
    }

    private MutationOutcome resizeClass(CanonicalModel model, UUID classId, Map<String, Object> payload) {
        return updateClass(model, classId, clazz -> {
            double width = doubleField(payload, "width", clazz.width());
            double height = doubleField(payload, "height", clazz.height());
            return new ClassEntity(clazz.id(), clazz.name(), clazz.visibility(), clazz.isAbstract(),
                    clazz.position(), width, height, clazz.attributes(), clazz.methods());
        });
    }

    private MutationOutcome renameClass(CanonicalModel model, UUID classId, Map<String, Object> payload) {
        return updateClass(model, classId, clazz -> {
            String name = stringField(payload, "name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("RENAME_CLASS requiere name");
            }
            return new ClassEntity(clazz.id(), name, clazz.visibility(), clazz.isAbstract(),
                    clazz.position(), clazz.width(), clazz.height(), clazz.attributes(), clazz.methods());
        });
    }

    // ---- UPDATE (merge parcial) ----

    private MutationOutcome updateAttribute(CanonicalModel model, UUID attributeId, Map<String, Object> payload) {
        for (ClassEntity clazz : model.classes()) {
            Optional<Attribute> existing = clazz.attributes().stream()
                    .filter(a -> a.id().equals(attributeId)).findFirst();
            if (existing.isPresent()) {
                Attribute merged = mergeAttribute(existing.get(), payload);
                ClassEntity updated = withAttributes(clazz, replaceAttribute(clazz.attributes(), merged));
                return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), clazz.id(), updated)));
            }
        }
        return MutationOutcome.rejected("Atributo no encontrado: " + attributeId);
    }

    private MutationOutcome updateMethod(CanonicalModel model, UUID methodId, Map<String, Object> payload) {
        for (ClassEntity clazz : model.classes()) {
            Optional<Method> existing = clazz.methods().stream()
                    .filter(m -> m.id().equals(methodId)).findFirst();
            if (existing.isPresent()) {
                Method merged = mergeMethod(existing.get(), payload);
                ClassEntity updated = withMethods(clazz, replaceMethod(clazz.methods(), merged));
                return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), clazz.id(), updated)));
            }
        }
        return MutationOutcome.rejected("Metodo no encontrado: " + methodId);
    }

    private MutationOutcome updateWaypoints(CanonicalModel model, UUID relationshipId, Map<String, Object> payload) {
        Optional<Relationship> found = findRelationship(model, relationshipId);
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Relacion no encontrada: " + relationshipId);
        }
        Object rawWaypoints = payload.get("waypoints");
        if (rawWaypoints == null) {
            throw new IllegalArgumentException("UPDATE_WAYPOINTS requiere waypoints");
        }
        List<Waypoint> waypoints = objectMapper.convertValue(rawWaypoints, new TypeReference<List<Waypoint>>() {
        });
        Relationship existing = found.get();
        Relationship updated = new Relationship(existing.id(), existing.sourceClassId(), existing.targetClassId(),
                existing.type(), existing.owningSide(), existing.joinTableName(), existing.sourceMultiplicity(),
                existing.targetMultiplicity(), existing.sourceRole(), existing.targetRole(), existing.isNavigable(),
                waypoints);
        return MutationOutcome.applied(withRelationships(model, replaceRelationship(model.relationships(), updated)));
    }

    /**
     * Solo type/multiplicidades/roles/owningSide/navegabilidad son mutables aqui;
     * id, sourceClassId, targetClassId y waypoints se preservan siempre para no
     * "destruir y recrear el conector" (seccion 8.1 del documento).
     */
    private MutationOutcome updateRelationship(CanonicalModel model, UUID relationshipId, Map<String, Object> payload) {
        Optional<Relationship> found = findRelationship(model, relationshipId);
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Relacion no encontrada: " + relationshipId);
        }
        Relationship existing = found.get();

        RelationshipType type = payload.containsKey("type")
                ? RelationshipType.valueOf(stringField(payload, "type")) : existing.type();
        OwningSide owningSide = payload.containsKey("owningSide")
                ? OwningSide.valueOf(stringField(payload, "owningSide")) : existing.owningSide();
        String joinTableName = payload.containsKey("joinTableName")
                ? stringField(payload, "joinTableName") : existing.joinTableName();
        Multiplicity sourceMultiplicity = payload.containsKey("sourceMultiplicity")
                ? Multiplicity.fromLiteral(stringField(payload, "sourceMultiplicity")) : existing.sourceMultiplicity();
        Multiplicity targetMultiplicity = payload.containsKey("targetMultiplicity")
                ? Multiplicity.fromLiteral(stringField(payload, "targetMultiplicity")) : existing.targetMultiplicity();
        String sourceRole = payload.containsKey("sourceRole") ? stringField(payload, "sourceRole") : existing.sourceRole();
        String targetRole = payload.containsKey("targetRole") ? stringField(payload, "targetRole") : existing.targetRole();
        boolean isNavigable = payload.containsKey("isNavigable")
                ? boolField(payload, "isNavigable", existing.isNavigable()) : existing.isNavigable();

        Relationship updated = new Relationship(existing.id(), existing.sourceClassId(), existing.targetClassId(),
                type, owningSide, joinTableName, sourceMultiplicity, targetMultiplicity, sourceRole, targetRole,
                isNavigable, existing.waypoints());
        return MutationOutcome.applied(withRelationships(model, replaceRelationship(model.relationships(), updated)));
    }

    private MutationOutcome updatePackage(CanonicalModel model, UUID packageId, Map<String, Object> payload) {
        Optional<Package> found = model.packages().stream().filter(p -> p.id().equals(packageId)).findFirst();
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Paquete no encontrado: " + packageId);
        }
        Package existing = found.get();
        String name = payload.containsKey("name") ? stringField(payload, "name") : existing.name();
        List<UUID> classIds = payload.containsKey("classIds")
                ? objectMapper.convertValue(payload.get("classIds"), new TypeReference<List<UUID>>() {
        })
                : existing.classIds();
        Package updated = new Package(existing.id(), name, classIds);
        List<Package> packages = model.packages().stream()
                .map(p -> p.id().equals(packageId) ? updated : p)
                .collect(Collectors.toList());
        return MutationOutcome.applied(withPackages(model, packages));
    }

    // ---- DELETE ----

    private MutationOutcome deleteClass(CanonicalModel model, UUID classId) {
        if (model.findClass(classId).isEmpty()) {
            return MutationOutcome.rejected("Clase no encontrada: " + classId);
        }
        List<ClassEntity> classes = model.classes().stream()
                .filter(c -> !c.id().equals(classId))
                .collect(Collectors.toList());
        // Cascada: limpia relaciones huerfanas que referenciaban la clase eliminada.
        List<Relationship> relationships = model.relationships().stream()
                .filter(r -> !r.sourceClassId().equals(classId) && !r.targetClassId().equals(classId))
                .collect(Collectors.toList());
        return MutationOutcome.applied(withRelationships(withClasses(model, classes), relationships));
    }

    private MutationOutcome deleteAttribute(CanonicalModel model, UUID attributeId) {
        for (ClassEntity clazz : model.classes()) {
            if (clazz.attributes().stream().anyMatch(a -> a.id().equals(attributeId))) {
                List<Attribute> attributes = clazz.attributes().stream()
                        .filter(a -> !a.id().equals(attributeId))
                        .collect(Collectors.toList());
                ClassEntity updated = withAttributes(clazz, attributes);
                return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), clazz.id(), updated)));
            }
        }
        return MutationOutcome.rejected("Atributo no encontrado: " + attributeId);
    }

    private MutationOutcome deleteMethod(CanonicalModel model, UUID methodId) {
        for (ClassEntity clazz : model.classes()) {
            if (clazz.methods().stream().anyMatch(m -> m.id().equals(methodId))) {
                List<Method> methods = clazz.methods().stream()
                        .filter(m -> !m.id().equals(methodId))
                        .collect(Collectors.toList());
                ClassEntity updated = withMethods(clazz, methods);
                return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), clazz.id(), updated)));
            }
        }
        return MutationOutcome.rejected("Metodo no encontrado: " + methodId);
    }

    private MutationOutcome deleteRelationship(CanonicalModel model, UUID relationshipId) {
        if (findRelationship(model, relationshipId).isEmpty()) {
            return MutationOutcome.rejected("Relacion no encontrada: " + relationshipId);
        }
        List<Relationship> relationships = model.relationships().stream()
                .filter(r -> !r.id().equals(relationshipId))
                .collect(Collectors.toList());
        return MutationOutcome.applied(withRelationships(model, relationships));
    }

    private MutationOutcome deletePackage(CanonicalModel model, UUID packageId) {
        boolean existed = model.packages().stream().anyMatch(p -> p.id().equals(packageId));
        if (!existed) {
            return MutationOutcome.rejected("Paquete no encontrado: " + packageId);
        }
        // Las clases contenidas se conservan; solo se elimina el paquete (seccion 8.1).
        List<Package> packages = model.packages().stream()
                .filter(p -> !p.id().equals(packageId))
                .collect(Collectors.toList());
        return MutationOutcome.applied(withPackages(model, packages));
    }

    // ---- BULK_MERGE ----

    private MutationOutcome bulkMerge(CanonicalModel model, Map<String, Object> payload) {
        List<ClassEntity> newClasses = payload.containsKey("classes")
                ? objectMapper.convertValue(payload.get("classes"), new TypeReference<List<ClassEntity>>() {
        })
                : List.of();
        List<Relationship> newRelationships = payload.containsKey("relationships")
                ? objectMapper.convertValue(payload.get("relationships"), new TypeReference<List<Relationship>>() {
        })
                : List.of();
        List<Package> newPackages = payload.containsKey("packages")
                ? objectMapper.convertValue(payload.get("packages"), new TypeReference<List<Package>>() {
        })
                : List.of();

        List<ClassEntity> classes = new ArrayList<>(model.classes());
        for (ClassEntity c : newClasses) {
            if (classes.stream().noneMatch(existing -> existing.id().equals(c.id()))) {
                classes.add(c);
            }
        }
        List<Relationship> relationships = new ArrayList<>(model.relationships());
        for (Relationship r : newRelationships) {
            if (relationships.stream().noneMatch(existing -> existing.id().equals(r.id()))) {
                relationships.add(r);
            }
        }
        List<Package> packages = new ArrayList<>(model.packages());
        for (Package p : newPackages) {
            if (packages.stream().noneMatch(existing -> existing.id().equals(p.id()))) {
                packages.add(p);
            }
        }
        return MutationOutcome.applied(
                new CanonicalModel(model.schemaVersion(), model.mutationVersion(), packages, classes, relationships));
    }

    // ---- helpers ----

    private interface ClassUpdater {
        ClassEntity update(ClassEntity existing);
    }

    private MutationOutcome updateClass(CanonicalModel model, UUID classId, ClassUpdater updater) {
        Optional<ClassEntity> found = model.findClass(classId);
        if (found.isEmpty()) {
            return MutationOutcome.rejected("Clase no encontrada: " + classId);
        }
        ClassEntity updated = updater.update(found.get());
        return MutationOutcome.applied(withClasses(model, replaceClass(model.classes(), classId, updated)));
    }

    private Attribute mergeAttribute(Attribute existing, Map<String, Object> payload) {
        return new Attribute(
                existing.id(),
                payload.containsKey("name") ? stringField(payload, "name") : existing.name(),
                payload.containsKey("type") ? AttributeType.valueOf(stringField(payload, "type")) : existing.type(),
                payload.containsKey("length") ? intField(payload, "length", existing.length()) : existing.length(),
                payload.containsKey("precision") ? intField(payload, "precision", existing.precision()) : existing.precision(),
                payload.containsKey("scale") ? intField(payload, "scale", existing.scale()) : existing.scale(),
                payload.containsKey("visibility") ? Visibility.valueOf(stringField(payload, "visibility")) : existing.visibility(),
                payload.containsKey("isPrimaryKey") ? boolField(payload, "isPrimaryKey", existing.isPrimaryKey()) : existing.isPrimaryKey(),
                payload.containsKey("isNullable") ? boolField(payload, "isNullable", existing.isNullable()) : existing.isNullable(),
                payload.containsKey("isUnique") ? boolField(payload, "isUnique", existing.isUnique()) : existing.isUnique(),
                payload.containsKey("defaultValue") ? stringField(payload, "defaultValue") : existing.defaultValue()
        );
    }

    private Method mergeMethod(Method existing, Map<String, Object> payload) {
        List<Parameter> parameters = payload.containsKey("parameters")
                ? objectMapper.convertValue(payload.get("parameters"), new TypeReference<List<Parameter>>() {
        })
                : existing.parameters();
        return new Method(
                existing.id(),
                payload.containsKey("name") ? stringField(payload, "name") : existing.name(),
                payload.containsKey("returnType") ? stringField(payload, "returnType") : existing.returnType(),
                payload.containsKey("visibility") ? Visibility.valueOf(stringField(payload, "visibility")) : existing.visibility(),
                parameters
        );
    }

    private Optional<Relationship> findRelationship(CanonicalModel model, UUID relationshipId) {
        return model.relationships().stream().filter(r -> r.id().equals(relationshipId)).findFirst();
    }

    private List<Relationship> replaceRelationship(List<Relationship> relationships, Relationship updated) {
        return relationships.stream()
                .map(r -> r.id().equals(updated.id()) ? updated : r)
                .collect(Collectors.toList());
    }

    private List<Attribute> replaceAttribute(List<Attribute> attributes, Attribute updated) {
        return attributes.stream()
                .map(a -> a.id().equals(updated.id()) ? updated : a)
                .collect(Collectors.toList());
    }

    private List<Method> replaceMethod(List<Method> methods, Method updated) {
        return methods.stream()
                .map(m -> m.id().equals(updated.id()) ? updated : m)
                .collect(Collectors.toList());
    }

    private ClassEntity withAttributes(ClassEntity clazz, List<Attribute> attributes) {
        return new ClassEntity(clazz.id(), clazz.name(), clazz.visibility(), clazz.isAbstract(), clazz.position(),
                clazz.width(), clazz.height(), attributes, clazz.methods());
    }

    private ClassEntity withMethods(ClassEntity clazz, List<Method> methods) {
        return new ClassEntity(clazz.id(), clazz.name(), clazz.visibility(), clazz.isAbstract(), clazz.position(),
                clazz.width(), clazz.height(), clazz.attributes(), methods);
    }

    private List<ClassEntity> replaceClass(List<ClassEntity> classes, UUID id, ClassEntity updated) {
        return classes.stream().map(c -> c.id().equals(id) ? updated : c).collect(Collectors.toList());
    }

    private <T> List<T> append(List<T> list, T item) {
        List<T> copy = new ArrayList<>(list);
        copy.add(item);
        return copy;
    }

    private CanonicalModel withClasses(CanonicalModel model, List<ClassEntity> classes) {
        return new CanonicalModel(model.schemaVersion(), model.mutationVersion(), model.packages(), classes, model.relationships());
    }

    private CanonicalModel withRelationships(CanonicalModel model, List<Relationship> relationships) {
        return new CanonicalModel(model.schemaVersion(), model.mutationVersion(), model.packages(), model.classes(), relationships);
    }

    private CanonicalModel withPackages(CanonicalModel model, List<Package> packages) {
        return new CanonicalModel(model.schemaVersion(), model.mutationVersion(), packages, model.classes(), model.relationships());
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    private static Double doubleField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
    }

    private static double doubleField(Map<String, Object> payload, String key, double defaultValue) {
        Double v = doubleField(payload, key);
        return v == null ? defaultValue : v;
    }

    private static int intField(Map<String, Object> payload, String key, int defaultValue) {
        Object v = payload.get(key);
        if (v == null) {
            return defaultValue;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static boolean boolField(Map<String, Object> payload, String key, boolean defaultValue) {
        Object v = payload.get(key);
        if (v == null) {
            return defaultValue;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }

    /**
     * @param applied true si la operacion se aplico y {@code model} contiene el nuevo estado
     * @param model   nuevo {@link CanonicalModel} resultante (null si no se aplico)
     * @param reason  motivo del rechazo (null si se aplico)
     */
    public record MutationOutcome(boolean applied, CanonicalModel model, String reason) {
        static MutationOutcome applied(CanonicalModel model) {
            return new MutationOutcome(true, model, null);
        }

        static MutationOutcome rejected(String reason) {
            return new MutationOutcome(false, null, reason);
        }
    }
}
