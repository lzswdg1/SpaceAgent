package com.spaceagent.platform.project.domain;
import java.time.Instant;
public record ProjectBlueprint(
        String id, String tenantId, String projectId, int versionNumber,
        ProjectBlueprintStatus status, ProjectBlueprintSource source,
        String sourceRepositoryId, String generatedByAgentId,
        String generatedByRunConfigurationSnapshotId,
        String createdBy, String confirmedBy, Instant confirmedAt,
        ProjectBlueprintDocument document, Instant createdAt, Instant updatedAt) {

    public ProjectBlueprint(
            String id, String tenantId, String projectId, int versionNumber,
            ProjectBlueprintStatus status, ProjectBlueprintSource source,
            String sourceRepositoryId, String ignoredLegacyGenerator,
            String createdBy, String confirmedBy, Instant confirmedAt,
            ProjectBlueprintDocument document, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, projectId, versionNumber, status, source, sourceRepositoryId,
                null, null, createdBy, confirmedBy, confirmedAt,
                document, createdAt, updatedAt);
    }
}
