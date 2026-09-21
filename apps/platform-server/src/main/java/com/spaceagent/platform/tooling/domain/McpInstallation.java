package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpInstallation(
        String id,
        String entryId,
        String serverVersionId,
        String serverVersion,
        String tenantId,
        String subjectId,
        String createdBy,
        McpInstallationScope scope,
        String displayName,
        McpInstallationState state,
        Instant createdAt,
        Instant updatedAt) {
}
