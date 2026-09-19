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
    private final String targetIdType;
    private final String targetIdGetterName;

    /**
     * {@code targetIdType}/{@code targetIdGetterName}: tipo Java y nombre del getter
     * del id "efectivo" de {@code targetClassName} (ver
     * {@code GeneratorModelBuilder.resolveEffectivePrimaryKeyAttribute}) -- solo
     * relevantes para {@link #isOwningSingleValued()} (los DTO exponen
     * {@code <fieldName>Id} con {@code targetIdType}, y {@code Mapper.java.ftl} llama
     * a {@code targetIdGetterName} sobre la entidad relacionada). {@code null} para
     * el resto de los {@code Kind} (no exponen un id de FK propio en el DTO/Mapper).
     *
     * <p>Bugs reales encontrados generando y COMPILANDO un backend real (diagrama
     * veterinaria, clases con PK declarada explícitamente con un tipo/nombre no
     * default): (1) el DTO exponía siempre {@code Long} para el id de FK sin importar
     * el tipo real de la PK referenciada ({@code findById(Long)} contra un
     * repositorio {@code JpaRepository<Veterinario, Integer>}); (2) el Mapper
     * generado llamaba siempre a {@code getId()} sobre la entidad relacionada, pero
     * una PK declarada con otro nombre (p.ej. "idCliente") genera el getter
     * {@code getIdCliente()}, no {@code getId()} -- "cannot find symbol: method
     * getId()".</p>
     */
    public RelationshipFieldView(Kind kind, String fieldName, String targetClassName, String joinColumnName,
                                  String mappedBy, String joinTableName, String joinColumnOwn,
                                  String joinColumnOther, boolean nullable, String targetIdType,
                                  String targetIdGetterName) {
        this.kind = kind;
        this.fieldName = fieldName;
        this.targetClassName = targetClassName;
        this.joinColumnName = joinColumnName;
        this.mappedBy = mappedBy;
        this.joinTableName = joinTableName;
        this.joinColumnOwn = joinColumnOwn;
        this.joinColumnOther = joinColumnOther;
        this.nullable = nullable;
        this.targetIdType = targetIdType;
        this.targetIdGetterName = targetIdGetterName;
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

    public String getTargetIdType() {
        return targetIdType;
    }

    public String getTargetIdGetterName() {
        return targetIdGetterName;
    }

    /** Nombre de campo capitalizado, para getters/setters generados ({@code getCliente}, {@code setClienteId}). */
    public String getCapitalizedFieldName() {
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }
}
