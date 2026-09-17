package com.modelcollab.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity mapping the {@code users} table (see section 13 of the data model).
 *
 * <p>UUID PK generation strategy: we use Hibernate 6's {@link UuidGenerator}
 * (generated in the application, random UUIDv4) rather than relying on the
 * database's {@code gen_random_uuid()} default. This keeps entity creation
 * consistent regardless of the underlying {@code ddl-auto} configuration and
 * lets us reference the generated id immediately after {@code persist()}
 * without a round-trip/refresh. The DB-side default in the DDL still acts as
 * a safety net for rows inserted outside of JPA.</p>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected User() {
        // JPA
    }

    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
