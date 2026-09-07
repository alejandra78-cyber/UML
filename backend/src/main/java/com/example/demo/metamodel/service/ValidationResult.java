package com.example.demo.metamodel.service;

import java.util.List;

/**
 * Resultado estructurado de validar un {@link com.example.demo.metamodel.model.CanonicalModel}.
 * Es la base del futuro endpoint {@code GET /api/v1/diagrams/{id}/validate} (seccion 14),
 * que no se implementa aqui.
 *
 * @param valid  true si no hay ningun issue con severidad ERROR
 * @param issues lista completa de errores y advertencias encontrados
 */
public record ValidationResult(boolean valid, List<ValidationIssue> issues) {
    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ValidationResult of(List<ValidationIssue> issues) {
        boolean hasErrors = issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        return new ValidationResult(!hasErrors, issues);
    }
}
