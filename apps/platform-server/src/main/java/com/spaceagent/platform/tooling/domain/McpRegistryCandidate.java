package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpRegistryCandidate(
        String id,
        String snapshotId,
        String sourceId,
        String sourceKey,
        String registryName,
        String registryVersion,
        McpRegistryReviewState reviewState,
        String reviewedBy,
        String reviewReason,
        Instant reviewedAt,
        String publishedEntryId,
        String publishedVersionId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
}
