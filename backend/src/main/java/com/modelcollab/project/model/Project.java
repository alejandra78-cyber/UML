package com.modelcollab.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the {@code projects} table.
 *
 * <p>The owner is stored as a plain {@code owner_id} UUID column (not a JPA
 * {@code @ManyToOne} to {@code User}) so this module stays loosely coupled
 * to the {@code auth} package and so that a simple derived query
 * ({@code findByOwnerId}) can be used on {@code ProjectRepository}. The
 * database-level {@code REFERENCES users(id)} foreign key still enforces
 * referential integrity.</p>
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected Project() {
        // JPA
    }

    public Project(String name, String description, UUID ownerId) {
        this.name = name;
        this.description = description;
        this.ownerId = ownerId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
