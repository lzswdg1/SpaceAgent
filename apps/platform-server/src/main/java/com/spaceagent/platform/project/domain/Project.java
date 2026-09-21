package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Tenant-scoped Project aggregate. State changes are expressed only through
 * aggregate behavior; persistence adapters use {@link #restore} to rehydrate it.
 */
public final class Project {

    private final String id;
    private final String tenantId;
    private final String ownerId;
    private final String name;
    private final String description;
    private final ProjectStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Project(
            String id,
            String tenantId,
            String ownerId,
            String name,
            String description,
            ProjectStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.ownerId = requireText(ownerId, "ownerId");
        this.name = normalizeName(name);
        this.description = description;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Project create(
            String id,
            String tenantId,
            String ownerId,
            String name,
            String description,
            Instant now) {
        return new Project(
                id, tenantId, ownerId, name, description,
                ProjectStatus.ACTIVE, now, now);
    }

    public static Project restore(
            String id,
            String tenantId,
            String ownerId,
            String name,
            String description,
            ProjectStatus status,
            Instant createdAt,
            Instant updatedAt) {
        return new Project(
                id, tenantId, ownerId, name, description,
                status, createdAt, updatedAt);
    }

    public Project rename(String newName, Instant now) {
        requireActive();
        return new Project(
                id, tenantId, ownerId, newName, description,
                status, createdAt, Objects.requireNonNull(now, "now"));
    }

    public Project updateDescription(String newDescription, Instant now) {
        requireActive();
        return new Project(
                id, tenantId, ownerId, name, newDescription,
                status, createdAt, Objects.requireNonNull(now, "now"));
    }

    public Project archive(Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            return this;
        }
        return new Project(
                id, tenantId, ownerId, name, description,
                ProjectStatus.ARCHIVED, createdAt, Objects.requireNonNull(now, "now"));
    }

    private void requireActive() {
        if (status != ProjectStatus.ACTIVE) {
            throw new IllegalStateException("Archived Project cannot be modified");
        }
    }

    private static String normalizeName(String value) {
        String normalized = requireText(value, "name");
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("name must not exceed 128 characters");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String ownerId() {
        return ownerId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ProjectStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
