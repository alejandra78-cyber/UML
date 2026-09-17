package com.modelcollab.metamodel.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catalogo de invariantes de "100% Compila" (seccion 11.2 del documento de
 * arquitectura) aplicado sobre un {@link CanonicalModel}. Produce un
 * {@link ValidationResult} con errores bloqueantes y advertencias; no genera
 * codigo ni persiste nada.
 */
@Service
public class MetamodelValidator {

    private final IdentifierSanitizer identifierSanitizer;

    public MetamodelValidator(IdentifierSanitizer identifierSanitizer) {
        this.identifierSanitizer = identifierSanitizer;
    }

    public ValidationResult validate(CanonicalModel model) {
        List<ValidationIssue> issues = new ArrayList<>();

        checkReservedWordCollisions(model, issues);
        checkMissingPrimaryKeys(model, issues);
        checkMultipleInheritance(model, issues);
        checkManyToManyOwningSideConsistency(model, issues);
        checkOrphanRelationships(model, issues);

        return ValidationResult.of(issues);
    }

    /**
     * 1. Nombres de clase/atributo que colisionan con palabras reservadas SQL o Java
     * se reportan como advertencias (la sanitizacion real la aplica {@link IdentifierSanitizer}).
     */
    private void checkReservedWordCollisions(CanonicalModel model, List<ValidationIssue> issues) {
        for (ClassEntity clazz : model.classes()) {
            if (identifierSanitizer.isSqlReserved(clazz.name())) {
                issues.add(ValidationIssue.warning("SQL_RESERVED_WORD",
                        "El nombre de clase '" + clazz.name() + "' colisiona con una palabra reservada SQL",
                        clazz.id().toString()));
            }
            for (Attribute attribute : clazz.attributes()) {
                if (identifierSanitizer.isSqlReserved(attribute.name())) {
                    issues.add(ValidationIssue.warning("SQL_RESERVED_WORD",
                            "El nombre de atributo '" + attribute.name() + "' en la clase '" + clazz.name()
                                    + "' colisiona con una palabra reservada SQL",
                            attribute.id().toString()));
                }
                if (identifierSanitizer.isJavaReserved(attribute.name())) {
                    issues.add(ValidationIssue.warning("JAVA_RESERVED_WORD",
                            "El nombre de atributo '" + attribute.name() + "' en la clase '" + clazz.name()
                                    + "' colisiona con una palabra reservada de Java",
                            attribute.id().toString()));
                }
            }
        }
    }

    /**
     * 2. Clases sin ningun atributo isPrimaryKey=true se reportan como advertencia
     * (en una fase futura el generador inyectaria una PK automatica).
     */
    private void checkMissingPrimaryKeys(CanonicalModel model, List<ValidationIssue> issues) {
        for (ClassEntity clazz : model.classes()) {
            boolean hasPk = clazz.attributes().stream().anyMatch(Attribute::isPrimaryKey);
            if (!hasPk) {
                issues.add(ValidationIssue.warning("NO_PRIMARY_KEY",
                        "La clase '" + clazz.name() + "' no tiene ningun atributo marcado como isPrimaryKey",
                        clazz.id().toString()));
            }
        }
    }

    /**
     * 3. Herencia multiple (mas de una relacion GENERALIZATION con la misma clase
     * fuente) no esta soportada en Java: error bloqueante.
     */
    private void checkMultipleInheritance(CanonicalModel model, List<ValidationIssue> issues) {
        Map<UUID, Integer> inheritanceCountBySource = new HashMap<>();
        for (Relationship relationship : model.relationships()) {
            if (relationship.type() == RelationshipType.GENERALIZATION) {
                inheritanceCountBySource.merge(relationship.sourceClassId(), 1, Integer::sum);
            }
        }
        inheritanceCountBySource.forEach((sourceClassId, count) -> {
            if (count > 1) {
                issues.add(ValidationIssue.error("MULTIPLE_GENERALIZATION",
                        "La clase " + sourceClassId + " tiene " + count
                                + " relaciones GENERALIZATION como fuente; Java no soporta herencia multiple",
                        sourceClassId.toString()));
            }
        });
    }

    /**
     * 4. Relaciones MANY_TO_MANY deben declarar owningSide de forma consistente
     * (obligatorio para que JPA sepa que lado es propietario de la tabla intermedia).
     */
    private void checkManyToManyOwningSideConsistency(CanonicalModel model, List<ValidationIssue> issues) {
        for (Relationship relationship : model.relationships()) {
            if (relationship.type() == RelationshipType.MANY_TO_MANY) {
                if (relationship.owningSide() == null) {
                    issues.add(ValidationIssue.error("MANY_TO_MANY_OWNING_SIDE_MISSING",
                            "La relacion MANY_TO_MANY " + relationship.id() + " no define owningSide",
                            relationship.id().toString()));
                }
                if (relationship.joinTableName() == null || relationship.joinTableName().isBlank()) {
                    issues.add(ValidationIssue.warning("MANY_TO_MANY_JOIN_TABLE_MISSING",
                            "La relacion MANY_TO_MANY " + relationship.id() + " no define joinTableName",
                            relationship.id().toString()));
                }
            }
        }
    }

    /**
     * 5. sourceClassId/targetClassId deben referenciar clases existentes; una
     * relacion huerfana es un error bloqueante.
     */
    private void checkOrphanRelationships(CanonicalModel model, List<ValidationIssue> issues) {
        Set<UUID> classIds = new HashSet<>();
        for (ClassEntity clazz : model.classes()) {
            classIds.add(clazz.id());
        }
        for (Relationship relationship : model.relationships()) {
            if (!classIds.contains(relationship.sourceClassId())) {
                issues.add(ValidationIssue.error("ORPHAN_RELATIONSHIP",
                        "La relacion " + relationship.id() + " referencia sourceClassId inexistente: "
                                + relationship.sourceClassId(),
                        relationship.id().toString()));
            }
            if (!classIds.contains(relationship.targetClassId())) {
                issues.add(ValidationIssue.error("ORPHAN_RELATIONSHIP",
                        "La relacion " + relationship.id() + " referencia targetClassId inexistente: "
                                + relationship.targetClassId(),
                        relationship.id().toString()));
            }
        }
    }
}
