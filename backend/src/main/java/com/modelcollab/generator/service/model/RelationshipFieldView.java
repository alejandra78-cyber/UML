package com.modelcollab.generator.service.model;

/**
 * Vista de un campo de relación JPA (derivado de {@code Relationship}) para las
 * plantillas de entidad del backend generado. {@code kind} determina qué bloque de
 * anotaciones imprime {@code Entity.java.ftl}.
 */
public class RelationshipFieldView {

    public enum Kind {
        MANY_TO_ONE,
        ONE_TO_MANY,
        ONE_TO_ONE_OWNING,
        ONE_TO_ONE_MAPPED,
        MANY_TO_MANY_OWNING,
        MANY_TO_MANY_MAPPED
    }

    private final Kind kind;
    private final String fieldName;
    private final String targetClassName;
    private final String joinColumnName;
    private final String mappedBy;
    private final String joinTableName;
    private final String joinColumnOwn;
    private final String joinColumnOther;
    private final boolean nullable;

    public RelationshipFieldView(Kind kind, String fieldName, String targetClassName, String joinColumnName,
                                  String mappedBy, String joinTableName, String joinColumnOwn,
                                  String joinColumnOther, boolean nullable) {
        this.kind = kind;
        this.fieldName = fieldName;
        this.targetClassName = targetClassName;
        this.joinColumnName = joinColumnName;
        this.mappedBy = mappedBy;
        this.joinTableName = joinTableName;
        this.joinColumnOwn = joinColumnOwn;
        this.joinColumnOther = joinColumnOther;
        this.nullable = nullable;
    }

    public String getKind() {
        return kind.name();
    }

    public boolean isCollection() {
        return kind == Kind.ONE_TO_MANY || kind == Kind.MANY_TO_MANY_OWNING || kind == Kind.MANY_TO_MANY_MAPPED;
    }

    /** true si esta clase es el lado dueño de una relación @ManyToOne/@OneToOne (para DTOs: exponer el id de la FK). */
    public boolean isOwningSingleValued() {
        return kind == Kind.MANY_TO_ONE || kind == Kind.ONE_TO_ONE_OWNING;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getTargetClassName() {
        return targetClassName;
    }

    public String getJoinColumnName() {
        return joinColumnName;
    }

    public String getMappedBy() {
        return mappedBy;
    }

    public String getJoinTableName() {
        return joinTableName;
    }

    public String getJoinColumnOwn() {
        return joinColumnOwn;
    }

    public String getJoinColumnOther() {
        return joinColumnOther;
    }

    public boolean isNullable() {
        return nullable;
    }

    /** Nombre de campo capitalizado, para getters/setters generados ({@code getCliente}, {@code setClienteId}). */
    public String getCapitalizedFieldName() {
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }
}
