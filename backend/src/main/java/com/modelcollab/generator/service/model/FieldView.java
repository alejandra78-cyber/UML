package com.modelcollab.generator.service.model;

/**
 * Vista de un atributo escalar (columna) para las plantillas FreeMarker de
 * entidad/DTO del backend generado.
 */
public class FieldView {

    private final String javaFieldName;
    private final String columnAnnotationLiteral;
    private final String javaType;
    private final boolean primaryKey;
    private final boolean nullable;
    private final boolean unique;
    private final int length;
    private final boolean varchar;

    public FieldView(String javaFieldName, String columnAnnotationLiteral, String javaType,
                      boolean primaryKey, boolean nullable, boolean unique, int length, boolean varchar) {
        this.javaFieldName = javaFieldName;
        this.columnAnnotationLiteral = columnAnnotationLiteral;
        this.javaType = javaType;
        this.primaryKey = primaryKey;
        this.nullable = nullable;
        this.unique = unique;
        this.length = length;
        this.varchar = varchar;
    }

    public String getJavaFieldName() {
        return javaFieldName;
    }

    /** Texto ya listo (con comillas) para {@code @Column(name = ...)}, ver {@code NameUtils.toJavaStringLiteral}. */
    public String getColumnAnnotationLiteral() {
        return columnAnnotationLiteral;
    }

    public String getJavaType() {
        return javaType;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isUnique() {
        return unique;
    }

    public int getLength() {
        return length;
    }

    public boolean isVarchar() {
        return varchar;
    }

    public String getGetterName() {
        String capitalized = Character.toUpperCase(javaFieldName.charAt(0)) + javaFieldName.substring(1);
        return "get" + capitalized;
    }

    public String getSetterName() {
        String capitalized = Character.toUpperCase(javaFieldName.charAt(0)) + javaFieldName.substring(1);
        return "set" + capitalized;
    }
}
