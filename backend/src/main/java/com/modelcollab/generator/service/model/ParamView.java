package com.modelcollab.generator.service.model;

/**
 * Vista de un parámetro de método para las plantillas FreeMarker de la capa de
 * servicio del backend generado.
 */
public class ParamView {

    private final String name;
    private final String type;

    public ParamView(String name, String type) {
        this.name = name;
        this.type = (type == null || type.isBlank()) ? "Object" : type;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }
}
