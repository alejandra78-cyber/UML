package com.example.demo.diagram.dto;

import java.util.List;

/**
 * Reconciliation/pre-validation report (section 9.2): how many operations
 * were applied vs. discarded, and why. Reusable both for the offline-sync
 * reconciliation report and for the generator's pre-validation report.
 */
public record ValidationReportDTO(
        int appliedCount,
        int discardedCount,
        List<String> discardedDetails
) {
}
