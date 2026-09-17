package com.modelcollab.diagram.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the {@code diagrams} table.
 *
 * <p><b>JSONB mapping choice:</b> {@code current_state} is mapped as a raw
 * {@link String} annotated with {@code @JdbcTypeCode(SqlTypes.JSON)}
 * (Hibernate 6/7 native JSON mapping, no extra dependency needed) rather
 * than as {@code Map<String,Object>}. Reasons:
 * <ul>
 *   <li>It keeps this entity fully decoupled from any particular JSON
 *   library. This repository resolves Jackson 3 ({@code tools.jackson.*})
 *   via {@code spring-boot-starter-webmvc} rather than classic Jackson 2
 *   ({@code com.fasterxml.jackson.databind}), and a {@code Map<String,Object>}
 *   field would still need (de)serialization somewhere -- Hibernate would
 *   pick whichever Jackson/Gson module is on the classpath, adding an
 *   implicit dependency this module shouldn't own.</li>
 *   <li>The other agent's {@code metamodel.model.CanonicalModel} is being
 *   built in parallel; storing the column as an opaque JSON string avoids
 *   binding this entity to that class before it stabilizes.</li>
 * </ul>
 * <p><b>TODO (for the review/integration pass):</b> once
 * {@code CanonicalModel} is stable and a JSON mapper is chosen for the
 * project, consider retyping {@code currentState} as
 * {@code CanonicalModel} (still with {@code @JdbcTypeCode(SqlTypes.JSON)})
 * so diagram services work with a typed object instead of a raw string.</p>
 *
 * <p><b>UUID PK generation:</b> {@code @GeneratedValue} + Hibernate's
 * {@code @UuidGenerator} (application-generated UUIDv4), consistent with
 * {@code auth.model.User} and {@code project.model.Project} in this
 * codebase.</p>
 */
@Entity
@Table(name = "diagrams")
public class Diagram {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    @Column(name = "mutation_version", nullable = false)
    private long mutationVersion;

    /**
     * Raw JSON text for the canonical diagram state. See class-level javadoc
     * for why this is {@code String} rather than {@code Map<String,Object>}
     * or a typed {@code CanonicalModel}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_state", nullable = false, columnDefinition = "jsonb")
    private String currentState;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Diagram() {
        // JPA
    }

    public Diagram(UUID projectId, String name, String schemaVersion, long mutationVersion, String currentState) {
        this.projectId = projectId;
        this.name = name;
        this.schemaVersion = schemaVersion;
        this.mutationVersion = mutationVersion;
        this.currentState = currentState;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public long getMutationVersion() {
        return mutationVersion;
    }

    public void setMutationVersion(long mutationVersion) {
        this.mutationVersion = mutationVersion;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
