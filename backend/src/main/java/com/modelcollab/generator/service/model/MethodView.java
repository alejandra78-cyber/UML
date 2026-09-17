package com.modelcollab.generator.service.model;

import java.util.List;

/**
 * Vista de un método de negocio UML para las plantillas de {@code service/} del
 * backend generado (invariante 5 del catálogo, sección 13.2 del plan
 * arquitectónico): se generan como firma en la interface y stub documentado
 * (comentario TODO + log) en la implementación.
 */
public class MethodView {

    private final String name;
    private final String returnType;
    private final List<ParamView> parameters;

    public MethodView(String name, String returnType, List<ParamView> parameters) {
        this.name = name;
        this.returnType = (returnType == null || returnType.isBlank()) ? "void" : returnType;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public String getReturnType() {
        return returnType;
    }

    public boolean isVoid() {
        return "void".equals(returnType);
    }

    public List<ParamView> getParameters() {
        return parameters;
    }

    /** Firma lista para usar en la interface/implementación: {@code "BigDecimal monto, String nota"}. */
    public String getParameterList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ParamView p = parameters.get(i);
            sb.append(p.getType()).append(' ').append(p.getName());
        }
        return sb.toString();
    }

    /** Lista de nombres de parámetros para el log del stub, p.ej. {@code "monto={}, nota={}"}. */
    public String getLogPlaceholders() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameters.get(i).getName()).append("={}");
        }
        return sb.toString();
    }

    public String getLogArgs() {
        StringBuilder sb = new StringBuilder();
        for (ParamView p : parameters) {
            sb.append(", ").append(p.getName());
        }
        return sb.toString();
    }
}
