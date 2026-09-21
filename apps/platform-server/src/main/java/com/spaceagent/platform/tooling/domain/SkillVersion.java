package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;

public record SkillVersion(
        String id,
        String skillId,
        int versionNumber,
        SkillVersionStatus status,
        String configHash,
        String instructions,
        List<String> requiredToolIds,
        String createdBy,
        Instant createdAt,
        String publishedBy,
        Instant publishedAt,
        String deprecatedBy,
        Instant deprecatedAt) {

    public SkillVersion {
        if (versionNumber <= 0) throw new IllegalArgumentException("versionNumber must be positive");
        if (configHash == null || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configHash must be a lowercase SHA-256 value");
        }
        requiredToolIds = requiredToolIds == null ? List.of() : List.copyOf(requiredToolIds);
    }

    public SkillVersion publish(String actorId, Instant now) {
        if (status != SkillVersionStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT SkillVersion can publish");
        }
        return new SkillVersion(
                id, skillId, versionNumber, SkillVersionStatus.PUBLISHED, configHash,
                instructions, requiredToolIds, createdBy, createdAt,
                actorId, now, deprecatedBy, deprecatedAt);
    }

    public SkillVersion deprecate(String actorId, Instant now) {
        if (status == SkillVersionStatus.DEPRECATED) return this;
        if (status != SkillVersionStatus.PUBLISHED) {
            throw new IllegalStateException("Only PUBLISHED SkillVersion can deprecate");
        }
        return new SkillVersion(
                id, skillId, versionNumber, SkillVersionStatus.DEPRECATED, configHash,
                instructions, requiredToolIds, createdBy, createdAt,
                publishedBy, publishedAt, actorId, now);
    }
}
