package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * A durable tenant.
 */
public record Tenant(
        String id,
        String name,
        String slug,
        TenantStatus status,
        String creatorUserId,
        Instant createdAt,
        Instant updatedAt,
        Instant deletionRequestedAt) {

    public Tenant(
            String id,
            String name,
            String slug,
            TenantStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, name, slug, status, null, createdAt, updatedAt, null);
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    public Tenant assignCreator(String userId, Instant now) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("creator userId is required");
        }
        return new Tenant(
                id, name, slug, status, userId.trim(), createdAt, now, deletionRequestedAt);
    }

    public Tenant updateMetadata(String updatedName, String updatedSlug, Instant now) {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Only an active Organization can be updated");
        }
        return new Tenant(
                id, updatedName, updatedSlug, status, creatorUserId,
                createdAt, now, deletionRequestedAt);
    }

    public Tenant markDeleting(Instant now) {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Only an active Organization can be deleted");
        }
        return new Tenant(
                id, name, slug, TenantStatus.DELETING, creatorUserId,
                createdAt, now, now);
    }

    public Tenant markDeleted(Instant now) {
        if (status != TenantStatus.DELETING && status != TenantStatus.DELETED) {
            throw new IllegalStateException("Only a deleting Organization can be finalized");
        }
        return new Tenant(
                id, name, slug, TenantStatus.DELETED, creatorUserId,
                createdAt, now, deletionRequestedAt);
    }
}
