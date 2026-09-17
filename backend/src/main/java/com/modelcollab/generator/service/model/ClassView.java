package com.modelcollab.generator.service.model;

import java.util.List;

/**
 * Vista completa de una {@code ClassEntity} del modelo canónico, ya resuelta a
 * nombres/tipos Java y anotaciones JPA, lista para alimentar las plantillas
 * FreeMarker de las 5 capas del backend generado (entidad, repositorio, DTO,
 * servicio, controlador).
 */
public class ClassView {

    private final String className;
    private final String tableAnnotationLiteral;
    private final String parentClassName;
    private final boolean inheritanceRoot;
    private final FieldView primaryKeyField;
    private final List<FieldView> fields;
    private final List<RelationshipFieldView> relationshipFields;
    private final List<MethodView> methods;
    private final String resourcePathPlural;
    private final String variableName;
    private final String effectivePrimaryKeyType;
    private final List<DtoFieldView> requestDtoFields;
    private final List<DtoFieldView> responseDtoFields;

    public ClassView(String className, String tableAnnotationLiteral, String parentClassName,
                      boolean inheritanceRoot, FieldView primaryKeyField, List<FieldView> fields,
                      List<RelationshipFieldView> relationshipFields, List<MethodView> methods,
                      String resourcePathPlural, String variableName, String effectivePrimaryKeyType,
                      List<DtoFieldView> requestDtoFields, List<DtoFieldView> responseDtoFields) {
        this.className = className;
        this.tableAnnotationLiteral = tableAnnotationLiteral;
        this.parentClassName = parentClassName;
        this.inheritanceRoot = inheritanceRoot;
        this.primaryKeyField = primaryKeyField;
        this.fields = fields;
        this.relationshipFields = relationshipFields;
        this.methods = methods;
        this.resourcePathPlural = resourcePathPlural;
        this.variableName = variableName;
        this.effectivePrimaryKeyType = effectivePrimaryKeyType;
        this.requestDtoFields = requestDtoFields;
        this.responseDtoFields = responseDtoFields;
    }

    public String getClassName() {
        return className;
    }

    public String getTableAnnotationLiteral() {
        return tableAnnotationLiteral;
    }

    public String getParentClassName() {
        return parentClassName;
    }

    public boolean isChild() {
        return parentClassName != null;
    }

    public boolean isInheritanceRoot() {
        return inheritanceRoot;
    }

    /** Null cuando la clase es hija (hereda el id del padre, ver {@link #isChild()}). */
    public FieldView getPrimaryKeyField() {
        return primaryKeyField;
    }

    public List<FieldView> getFields() {
        return fields;
    }

    public List<RelationshipFieldView> getRelationshipFields() {
        return relationshipFields;
    }

    /** Solo las relaciones ManyToOne/OneToOne dueñas (para exponer su id en los DTOs). */
    public List<RelationshipFieldView> getOwningSingleValuedRelationships() {
        return relationshipFields.stream().filter(RelationshipFieldView::isOwningSingleValued).toList();
    }

    public List<MethodView> getMethods() {
        return methods;
    }

    public String getRepositoryName() {
        return className + "Repository";
    }

    public String getServiceName() {
        return className + "Service";
    }

    public String getServiceImplName() {
        return className + "ServiceImpl";
    }

    public String getControllerName() {
        return className + "Controller";
    }

    public String getRequestDtoName() {
        return className + "RequestDTO";
    }

    public String getResponseDtoName() {
        return className + "ResponseDTO";
    }

    public String getMapperName() {
        return className + "Mapper";
    }

    /** Ruta REST en plural minúsculas, p.ej. {@code "clientes"} para {@code /api/clientes}. */
    public String getResourcePathPlural() {
        return resourcePathPlural;
    }

    /** Nombre de variable camelCase para esta clase, p.ej. {@code "cliente"}. */
    public String getVariableName() {
        return variableName;
    }

    /**
     * Tipo Java del id efectivo de esta clase para {@code JpaRepository<Entity, ?>}
     * y los {@code @PathVariable} del controlador: el propio si no es hija, o el de
     * la raíz de herencia si lo es (las hijas no declaran su propio {@code @Id}).
     */
    public String getEffectivePrimaryKeyType() {
        return effectivePrimaryKeyType;
    }

    /** Parámetros del record {@code RequestDTO}: atributos escalares + ids de FK dueñas, con validación. */
    public List<DtoFieldView> getRequestDtoFields() {
        return requestDtoFields;
    }

    /** Parámetros del record {@code ResponseDTO}: id + atributos escalares + ids de FK dueñas. */
    public List<DtoFieldView> getResponseDtoFields() {
        return responseDtoFields;
    }
}
