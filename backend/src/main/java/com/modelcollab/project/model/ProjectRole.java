package com.modelcollab.project.model;

/**
 * Mirrors the {@code CHECK (role IN ('OWNER','EDITOR','VIEWER'))} constraint
 * on {@code project_members.role}. The constraint itself lives in the
 * database; this enum is the Java-side reflection of the same closed set of
 * values, mapped with {@code @Enumerated(EnumType.STRING)}.
 */
public enum ProjectRole {
    OWNER,
    EDITOR,
    VIEWER
}
