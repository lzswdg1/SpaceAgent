package com.spaceagent.platform.shared.api;

import java.time.Instant;

/**
 * Redacted, type-tagged metadata used only by the non-tenant administration read plane.
 * Content, prompts, filesystem paths, Tool arguments/results and credential material are excluded.
 */
public record UserResourceSummary(
        String kind,
        String id,
        String organizationId,
        String parentId,
        String displayName,
        String state,
        String relation,
        Instant createdAt,
        Instant updatedAt,
        String safeErrorCode,
        long primaryCount,
        long secondaryCount) {
}
