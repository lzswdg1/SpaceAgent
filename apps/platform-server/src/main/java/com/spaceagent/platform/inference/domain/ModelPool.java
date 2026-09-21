package com.spaceagent.platform.inference.domain;

import java.time.Instant;

/** Stable Organization/user-owned model routing entry point. */
public record ModelPool(
        String id,
        String tenantId,
        String ownerId,
        String name,
        ModelPoolVisibility visibility,
        ModelPoolRoutingStrategy routingStrategy,
        boolean fallbackEnabled,
        ModelPoolStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public ModelPool activate(Instant now) {
        return new ModelPool(
                id, tenantId, ownerId, name, visibility, routingStrategy,
                fallbackEnabled, ModelPoolStatus.ACTIVE, createdAt, now);
    }

    public ModelPool markDraft(Instant now) {
        return new ModelPool(
                id, tenantId, ownerId, name, visibility, routingStrategy,
                fallbackEnabled, ModelPoolStatus.DRAFT, createdAt, now);
    }

    public ModelPool disable(Instant now) {
        return new ModelPool(
                id, tenantId, ownerId, name, visibility, routingStrategy,
                fallbackEnabled, ModelPoolStatus.DISABLED, createdAt, now);
    }
}
