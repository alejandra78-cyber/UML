package com.modelcollab.metamodel.service;

/**
 * Un hallazgo (error bloqueante o advertencia) producido por {@link MetamodelValidator}.
 *
 * @param severity   ERROR (bloqueante) o WARNING (informativo)
 * @param code       codigo estable para localizacion/pruebas, p.ej. "ORPHAN_RELATIONSHIP"
 * @param message    mensaje legible para humanos
 * @param relatedId  id (como String) del elemento relacionado con el hallazgo, o null
 */
public record ValidationIssue(Severity severity, String code, String message, String relatedId) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public static ValidationIssue error(String code, String message, String relatedId) {
        return new ValidationIssue(Severity.ERROR, code, message, relatedId);
    }

    public static ValidationIssue warning(String code, String message, String relatedId) {
        return new ValidationIssue(Severity.WARNING, code, message, relatedId);
    }
}
