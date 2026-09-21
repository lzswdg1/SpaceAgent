package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record SkillDefinition(
        String id,
        String tenantId,
        String ownerUserId,
        String name,
        String description,
        SkillLifecycle lifecycle,
        String currentVersionId,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public SkillDefinition {
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
    }

    public SkillDefinition publish(String versionId, Instant now) {
        if (lifecycle != SkillLifecycle.ACTIVE) {
            throw new IllegalStateException("Archived Skill cannot publish a version");
        }
        return new SkillDefinition(
                id, tenantId, ownerUserId, name, description, lifecycle,
                versionId, revision + 1, createdAt, now, archivedAt);
    }

    public SkillDefinition clearCurrentVersion(Instant now) {
        return new SkillDefinition(
                id, tenantId, ownerUserId, name, description, lifecycle,
                null, revision + 1, createdAt, now, archivedAt);
    }

    public SkillDefinition archive(Instant now) {
        return new SkillDefinition(
                id, tenantId, ownerUserId, name, description, SkillLifecycle.ARCHIVED,
                null, revision + 1, createdAt, now, now);
    }
}
