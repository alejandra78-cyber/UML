package com.modelcollab.generator.service.model;

import java.util.List;

/**
 * Vista de un parámetro de {@code RequestDTO}/{@code ResponseDTO} (record). Existe
 * como clase separada de {@link FieldView} porque los DTOs combinan dos fuentes
 * distintas en una sola lista de parámetros de record -- atributos escalares y, para
 * las relaciones {@code @ManyToOne}/{@code @OneToOne} dueñas, el id de la FK (p.ej.
 * {@code clienteId}) -- y las plantillas FreeMarker necesitan una única lista
 * uniforme para poder separar los parámetros con comas sin lógica frágil de "es el
 * último elemento de cuál de las dos listas".
 */
public class DtoFieldView {

    private final String javaType;
    private final String javaFieldName;
    private final List<String> annotations;

    public DtoFieldView(String javaType, String javaFieldName, List<String> annotations) {
        this.javaType = javaType;
        this.javaFieldName = javaFieldName;
        this.annotations = annotations;
    }

    public String getJavaType() {
        return javaType;
    }

    public String getJavaFieldName() {
        return javaFieldName;
    }

    public List<String> getAnnotations() {
        return annotations;
    }
}
