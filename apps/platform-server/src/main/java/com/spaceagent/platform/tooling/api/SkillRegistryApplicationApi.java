package com.spaceagent.platform.tooling.api;

import java.time.Instant;
import java.util.List;

public interface SkillRegistryApplicationApi {

    SkillView create(CreateSkillCommand command);

    SkillVersionView createVersion(CreateSkillVersionCommand command);

    SkillView publish(SkillVersionLifecycleCommand command);

    SkillView deprecate(SkillVersionLifecycleCommand command);

    void archive(ArchiveSkillCommand command);

    SkillView get(String tenantId, String userId, String skillId);

    List<SkillView> list(String tenantId, String userId);

    record CreateSkillCommand(
            String tenantId, String userId, String name, String description,
            String instructions, List<String> requiredToolIds) { }

    record CreateSkillVersionCommand(
            String tenantId, String userId, String skillId,
            String instructions, List<String> requiredToolIds) { }

    record SkillVersionLifecycleCommand(
            String tenantId, String userId, String skillId, String skillVersionId) { }

    record ArchiveSkillCommand(String tenantId, String userId, String skillId) { }

    record SkillView(
            String id, String tenantId, String ownerUserId, String name, String description,
            String lifecycle, String currentVersionId, long revision,
            Instant createdAt, Instant updatedAt, Instant archivedAt,
            List<SkillVersionView> versions) {
        public SkillView {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    record SkillVersionView(
            String id, String skillId, int versionNumber, String status, String configHash,
            String instructions, List<String> requiredToolIds, String createdBy,
            Instant createdAt, String publishedBy, Instant publishedAt,
            String deprecatedBy, Instant deprecatedAt) {
        public SkillVersionView {
            requiredToolIds = requiredToolIds == null ? List.of() : List.copyOf(requiredToolIds);
        }
    }
}
