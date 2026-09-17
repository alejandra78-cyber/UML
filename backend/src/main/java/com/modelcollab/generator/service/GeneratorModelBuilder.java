package com.modelcollab.generator.service;

import com.modelcollab.generator.service.model.ClassView;
import com.modelcollab.generator.service.model.DtoFieldView;
import com.modelcollab.generator.service.model.FieldView;
import com.modelcollab.generator.service.model.MethodView;
import com.modelcollab.generator.service.model.ParamView;
import com.modelcollab.generator.service.model.RelationshipFieldView;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Traduce un {@link CanonicalModel} (ya validado por {@code MetamodelValidator}) a
 * la lista de {@link ClassView} que consumen las plantillas FreeMarker de
 * {@link SpringBootGeneratorService}. Aquí vive toda la lógica de mapeo
 * "modelo canónico -> anotaciones JPA" (invariantes 1, 2 y 4 de la sección 13.2 del
 * plan arquitectónico); las plantillas en sí solo iteran listas y no deciden nada.
 *
 * <p><b>Convención de multiplicidad asumida</b> (el JSON Schema canónico, sección 9
 * del plan, no la explicita): siguiendo la notación UML estándar, la multiplicidad
 * escrita junto a un extremo de la asociación indica cuántas instancias de la clase
 * de <em>ese</em> extremo participan por cada instancia del otro extremo. Es decir,
 * {@code targetMultiplicity} en {@code "0..*"}/{@code "1..*"} con
 * {@code sourceMultiplicity} en {@code "0..1"}/{@code "1..1"} se lee "una fuente
 * tiene muchos targets", y por lo tanto la fuente recibe {@code @OneToMany} y el
 * target {@code @ManyToOne}.</p>
 */
@Component
public class GeneratorModelBuilder {

    private final IdentifierSanitizer identifierSanitizer;

    public GeneratorModelBuilder(IdentifierSanitizer identifierSanitizer) {
        this.identifierSanitizer = identifierSanitizer;
    }

    /**
     * Construye la lista de {@link ClassView}, en el mismo orden que
     * {@code model.classes()}.
     */
    public List<ClassView> build(CanonicalModel model) {
        Map<UUID, ClassEntity> classesById = new LinkedHashMap<>();
        for (ClassEntity c : model.classes()) {
            classesById.put(c.id(), c);
        }

        // --- 1. Herencia: mapa hijo -> padre (GENERALIZATION: sourceClassId = hijo, targetClassId = padre;
        // ver MetamodelValidator.checkMultipleInheritance, que cuenta ocurrencias por sourceClassId). ---
        Map<UUID, UUID> parentOf = new HashMap<>();
        Set<UUID> parentIds = new HashSet<>();
        for (Relationship r : model.relationships()) {
            if (r.type() == RelationshipType.GENERALIZATION) {
                parentOf.put(r.sourceClassId(), r.targetClassId());
                parentIds.add(r.targetClassId());
            }
        }

        // --- 2. Nombres de clase Java ya resueltos, reutilizados en todo el resto del build. ---
        Map<UUID, String> classJavaNames = new HashMap<>();
        for (ClassEntity c : model.classes()) {
            classJavaNames.put(c.id(), NameUtils.pascalCase(c.name()));
        }

        // --- 3. Planes de relación (no-herencia), uno por Relationship, resueltos una sola vez
        // para que ambos lados (source/target) usen el mismo fieldName en owningSide/mappedBy. ---
        Map<UUID, List<RelationshipFieldView>> relFieldsByClass = new HashMap<>();
        Map<UUID, Set<String>> usedFieldNamesByClass = new HashMap<>();
        for (ClassEntity c : model.classes()) {
            relFieldsByClass.put(c.id(), new ArrayList<>());
            usedFieldNamesByClass.put(c.id(), new HashSet<>());
        }

        for (Relationship r : model.relationships()) {
            if (r.type() == RelationshipType.GENERALIZATION) {
                continue; // manejado aparte como herencia Java, no como campo
            }
            if (!classesById.containsKey(r.sourceClassId()) || !classesById.containsKey(r.targetClassId())) {
                continue; // relación huérfana: MetamodelValidator ya la bloquea como ERROR antes de llegar aquí
            }
            addRelationshipPlan(r, classJavaNames, relFieldsByClass, usedFieldNamesByClass);
        }

        // --- 4. Ensamblar un ClassView por cada ClassEntity. ---
        List<ClassView> views = new ArrayList<>();
        for (ClassEntity c : model.classes()) {
            views.add(buildClassView(c, classesById, classJavaNames, parentOf, parentIds, relFieldsByClass));
        }
        return views;
    }

    private void addRelationshipPlan(Relationship r, Map<UUID, String> classJavaNames,
                                      Map<UUID, List<RelationshipFieldView>> relFieldsByClass,
                                      Map<UUID, Set<String>> usedFieldNamesByClass) {
        UUID sourceId = r.sourceClassId();
        UUID targetId = r.targetClassId();
        String sourceClassName = classJavaNames.get(sourceId);
        String targetClassName = classJavaNames.get(targetId);

        if (r.type() == RelationshipType.MANY_TO_MANY) {
            addManyToMany(r, sourceId, targetId, sourceClassName, targetClassName, relFieldsByClass, usedFieldNamesByClass);
            return;
        }

        // ASSOCIATION / AGGREGATION / COMPOSITION / DEPENDENCY / REALIZATION: se mapean todas igual
        // a nivel de cardinalidad JPA (DEPENDENCY/REALIZATION no deberian traer multiplicidad real de
        // datos, pero si el modelo las trae, se tratan como asociacion estructural best-effort).
        boolean sourceMany = isMany(r.sourceMultiplicity());
        boolean targetMany = isMany(r.targetMultiplicity());

        if (sourceMany && targetMany) {
            // Asociación N..* <-> N..* sin ser MANY_TO_MANY explícita: se trata igual que
            // MANY_TO_MANY (tabla intermedia), usando owningSide (por defecto SOURCE si no viene).
            addManyToMany(r, sourceId, targetId, sourceClassName, targetClassName, relFieldsByClass, usedFieldNamesByClass);
            return;
        }

        if (sourceMany) {
            // Muchas fuentes por un target: fuente = ManyToOne (dueña), target = OneToMany.
            String fieldOnSource = uniqueFieldName(usedFieldNamesByClass.get(sourceId), fieldNameFor(r.sourceRole(), targetClassName, false));
            String fieldOnTarget = uniqueFieldName(usedFieldNamesByClass.get(targetId), fieldNameFor(r.targetRole(), sourceClassName, true));
            String joinColumn = NameUtils.snakeCase(targetClassName) + "_id";
            boolean nullableFk = r.targetMultiplicity() == null || r.targetMultiplicity() == Multiplicity.ZERO_ONE
                    || r.targetMultiplicity() == Multiplicity.ZERO_MANY;
            relFieldsByClass.get(sourceId).add(new RelationshipFieldView(RelationshipFieldView.Kind.MANY_TO_ONE,
                    fieldOnSource, targetClassName, joinColumn, null, null, null, null, nullableFk));
            relFieldsByClass.get(targetId).add(new RelationshipFieldView(RelationshipFieldView.Kind.ONE_TO_MANY,
                    fieldOnTarget, sourceClassName, null, fieldOnSource, null, null, null, true));
        } else if (targetMany) {
            // Una fuente, muchos targets: fuente = OneToMany, target = ManyToOne (dueño).
            String fieldOnTarget = uniqueFieldName(usedFieldNamesByClass.get(targetId), fieldNameFor(r.targetRole(), sourceClassName, false));
            String fieldOnSource = uniqueFieldName(usedFieldNamesByClass.get(sourceId), fieldNameFor(r.sourceRole(), targetClassName, true));
            String joinColumn = NameUtils.snakeCase(sourceClassName) + "_id";
            boolean nullableFk = r.sourceMultiplicity() == null || r.sourceMultiplicity() == Multiplicity.ZERO_ONE
                    || r.sourceMultiplicity() == Multiplicity.ZERO_MANY;
            relFieldsByClass.get(targetId).add(new RelationshipFieldView(RelationshipFieldView.Kind.MANY_TO_ONE,
                    fieldOnTarget, sourceClassName, joinColumn, null, null, null, null, nullableFk));
            relFieldsByClass.get(sourceId).add(new RelationshipFieldView(RelationshipFieldView.Kind.ONE_TO_MANY,
                    fieldOnSource, targetClassName, null, fieldOnTarget, null, null, null, true));
        } else {
            // 1..1 <-> 1..1 (o 0..1 <-> 0..1): OneToOne; el dueño lo decide owningSide (SOURCE por defecto).
            OwningSide owningSide = r.owningSide() == null ? OwningSide.SOURCE : r.owningSide();
            UUID owningId = owningSide == OwningSide.SOURCE ? sourceId : targetId;
            UUID otherId = owningSide == OwningSide.SOURCE ? targetId : sourceId;
            String owningClassName = owningSide == OwningSide.SOURCE ? sourceClassName : targetClassName;
            String otherClassName = owningSide == OwningSide.SOURCE ? targetClassName : sourceClassName;
            String roleForOwning = owningSide == OwningSide.SOURCE ? r.sourceRole() : r.targetRole();
            String roleForOther = owningSide == OwningSide.SOURCE ? r.targetRole() : r.sourceRole();

            String fieldOnOwning = uniqueFieldName(usedFieldNamesByClass.get(owningId), fieldNameFor(roleForOwning, otherClassName, false));
            String fieldOnOther = uniqueFieldName(usedFieldNamesByClass.get(otherId), fieldNameFor(roleForOther, owningClassName, false));
            String joinColumn = NameUtils.snakeCase(otherClassName) + "_id";

            relFieldsByClass.get(owningId).add(new RelationshipFieldView(RelationshipFieldView.Kind.ONE_TO_ONE_OWNING,
                    fieldOnOwning, otherClassName, joinColumn, null, null, null, null, true));
            relFieldsByClass.get(otherId).add(new RelationshipFieldView(RelationshipFieldView.Kind.ONE_TO_ONE_MAPPED,
                    fieldOnOther, owningClassName, null, fieldOnOwning, null, null, null, true));
        }
    }

    private void addManyToMany(Relationship r, UUID sourceId, UUID targetId, String sourceClassName,
                                String targetClassName, Map<UUID, List<RelationshipFieldView>> relFieldsByClass,
                                Map<UUID, Set<String>> usedFieldNamesByClass) {
        OwningSide owningSide = r.owningSide() == null ? OwningSide.SOURCE : r.owningSide();
        UUID owningId = owningSide == OwningSide.SOURCE ? sourceId : targetId;
        UUID otherId = owningSide == OwningSide.SOURCE ? targetId : sourceId;
        String owningClassName = owningSide == OwningSide.SOURCE ? sourceClassName : targetClassName;
        String otherClassName = owningSide == OwningSide.SOURCE ? targetClassName : sourceClassName;
        String roleForOwning = owningSide == OwningSide.SOURCE ? r.sourceRole() : r.targetRole();
        String roleForOther = owningSide == OwningSide.SOURCE ? r.targetRole() : r.sourceRole();

        String joinTableName = (r.joinTableName() == null || r.joinTableName().isBlank())
                ? NameUtils.snakeCase(owningClassName) + "_" + NameUtils.snakeCase(otherClassName)
                : r.joinTableName();
        String joinColumnOwn = NameUtils.snakeCase(owningClassName) + "_id";
        String joinColumnOther = NameUtils.snakeCase(otherClassName) + "_id";

        String fieldOnOwning = uniqueFieldName(usedFieldNamesByClass.get(owningId), fieldNameFor(roleForOwning, otherClassName, true));
        String fieldOnOther = uniqueFieldName(usedFieldNamesByClass.get(otherId), fieldNameFor(roleForOther, owningClassName, true));

        relFieldsByClass.get(owningId).add(new RelationshipFieldView(RelationshipFieldView.Kind.MANY_TO_MANY_OWNING,
                fieldOnOwning, otherClassName, null, null, joinTableName, joinColumnOwn, joinColumnOther, true));
        relFieldsByClass.get(otherId).add(new RelationshipFieldView(RelationshipFieldView.Kind.MANY_TO_MANY_MAPPED,
                fieldOnOther, owningClassName, null, fieldOnOwning, joinTableName, null, null, true));
    }

    private static boolean isMany(Multiplicity multiplicity) {
        return multiplicity == Multiplicity.ZERO_MANY || multiplicity == Multiplicity.ONE_MANY;
    }

    private static String fieldNameFor(String role, String otherClassName, boolean collection) {
        String base = (role != null && !role.isBlank()) ? NameUtils.camelCase(role) : NameUtils.camelCase(otherClassName);
        if (collection && (role == null || role.isBlank())) {
            return base + "List";
        }
        return base;
    }

    private static String uniqueFieldName(Set<String> used, String candidate) {
        String result = candidate;
        int suffix = 2;
        while (!used.add(result)) {
            result = candidate + suffix;
            suffix++;
        }
        return result;
    }

    private ClassView buildClassView(ClassEntity c, Map<UUID, ClassEntity> classesById,
                                      Map<UUID, String> classJavaNames, Map<UUID, UUID> parentOf,
                                      Set<UUID> parentIds, Map<UUID, List<RelationshipFieldView>> relFieldsByClass) {
        String className = classJavaNames.get(c.id());
        var tableSql = identifierSanitizer.sanitizeSqlIdentifier(NameUtils.snakeCase(c.name()));
        String tableAnnotationLiteral = NameUtils.toJavaStringLiteral(tableSql.quotedName());

        boolean isChild = parentOf.containsKey(c.id());
        String parentClassName = isChild ? classJavaNames.get(parentOf.get(c.id())) : null;
        boolean isInheritanceRoot = parentIds.contains(c.id());

        boolean hasDeclaredPk = c.attributes().stream().anyMatch(Attribute::isPrimaryKey);

        List<FieldView> fields = new ArrayList<>();
        FieldView primaryKeyField = null;

        if (!isChild) {
            if (hasDeclaredPk) {
                Attribute pkAttribute = c.attributes().stream().filter(Attribute::isPrimaryKey).findFirst().orElseThrow();
                primaryKeyField = toFieldView(pkAttribute, true);
            } else {
                // Invariante 2 (seccion 13.2): inyeccion automatica de PK si ninguna esta marcada.
                primaryKeyField = new FieldView("id", NameUtils.toJavaStringLiteral("id"), "Long",
                        true, false, true, 0, false);
            }
        }

        for (Attribute a : c.attributes()) {
            if (a.isPrimaryKey() && !isChild) {
                continue; // ya está como primaryKeyField
            }
            fields.add(toFieldView(a, false));
        }

        List<RelationshipFieldView> relationshipFields = relFieldsByClass.getOrDefault(c.id(), List.of());

        List<MethodView> methods = new ArrayList<>();
        for (Method m : c.methods()) {
            List<ParamView> params = new ArrayList<>();
            for (var p : m.parameters()) {
                params.add(new ParamView(NameUtils.camelCase(p.name()), p.type()));
            }
            methods.add(new MethodView(NameUtils.camelCase(m.name()), m.returnType(), params));
        }

        String variableName = NameUtils.camelCase(className);
        String resourcePathPlural = NameUtils.pluralize(NameUtils.camelCase(className).toLowerCase());

        List<RelationshipFieldView> owningSingleValued = relationshipFields.stream()
                .filter(RelationshipFieldView::isOwningSingleValued).toList();

        String effectivePrimaryKeyType = resolveEffectivePrimaryKeyType(c, classesById, parentOf);

        List<DtoFieldView> requestDtoFields = new ArrayList<>();
        for (FieldView f : fields) {
            requestDtoFields.add(new DtoFieldView(f.getJavaType(), f.getJavaFieldName(), requestAnnotationsFor(f)));
        }
        for (RelationshipFieldView rf : owningSingleValued) {
            List<String> annotations = rf.isNullable() ? List.of() : List.of("@NotNull");
            requestDtoFields.add(new DtoFieldView("Long", rf.getFieldName() + "Id", annotations));
        }

        List<DtoFieldView> responseDtoFields = new ArrayList<>();
        if (primaryKeyField != null) {
            responseDtoFields.add(new DtoFieldView(primaryKeyField.getJavaType(), primaryKeyField.getJavaFieldName(), List.of()));
        }
        for (FieldView f : fields) {
            responseDtoFields.add(new DtoFieldView(f.getJavaType(), f.getJavaFieldName(), List.of()));
        }
        for (RelationshipFieldView rf : owningSingleValued) {
            responseDtoFields.add(new DtoFieldView("Long", rf.getFieldName() + "Id", List.of()));
        }

        return new ClassView(className, tableAnnotationLiteral, parentClassName, isInheritanceRoot,
                primaryKeyField, fields, relationshipFields, methods, resourcePathPlural, variableName,
                effectivePrimaryKeyType, requestDtoFields, responseDtoFields);
    }

    /**
     * Tipo Java del id "efectivo" de una clase: el propio si no es hija, o el de la
     * raíz de herencia (recorriendo {@code parentOf}) si lo es -- las hijas no
     * declaran su propio {@code @Id} (ver {@link ClassView#isChild()}).
     */
    private String resolveEffectivePrimaryKeyType(ClassEntity c, Map<UUID, ClassEntity> classesById,
                                                    Map<UUID, UUID> parentOf) {
        UUID rootId = c.id();
        Set<UUID> visited = new HashSet<>();
        while (parentOf.containsKey(rootId) && visited.add(rootId)) {
            rootId = parentOf.get(rootId);
        }
        ClassEntity root = classesById.get(rootId);
        if (root == null) {
            return "Long";
        }
        return root.attributes().stream()
                .filter(Attribute::isPrimaryKey)
                .findFirst()
                .map(a -> javaType(a.type()))
                .orElse("Long");
    }

    private static List<String> requestAnnotationsFor(FieldView f) {
        List<String> annotations = new ArrayList<>();
        if (!f.isNullable()) {
            annotations.add(f.isVarchar() ? "@NotBlank" : "@NotNull");
        }
        if (f.isVarchar() && f.getLength() > 0) {
            annotations.add("@Size(max = " + f.getLength() + ")");
        }
        return annotations;
    }

    private FieldView toFieldView(Attribute a, boolean primaryKey) {
        var javaFieldMapping = identifierSanitizer.sanitizeJavaFieldName(NameUtils.camelCase(a.name()));
        var columnSql = identifierSanitizer.sanitizeSqlIdentifier(NameUtils.snakeCase(a.name()));
        String columnAnnotationLiteral = NameUtils.toJavaStringLiteral(columnSql.quotedName());
        boolean isVarchar = a.type() == AttributeType.VARCHAR;
        return new FieldView(javaFieldMapping.javaFieldName(), columnAnnotationLiteral, javaType(a.type()),
                primaryKey, a.isNullable(), a.isUnique(), a.length(), isVarchar);
    }

    private static String javaType(AttributeType type) {
        return switch (type) {
            case INTEGER -> "Integer";
            case BIGINT -> "Long";
            case VARCHAR, TEXT -> "String";
            case DECIMAL -> "java.math.BigDecimal";
            case BOOLEAN -> "Boolean";
            case DATE -> "java.time.LocalDate";
            case DATETIME -> "java.time.LocalDateTime";
            case UUID -> "java.util.UUID";
        };
    }
}
