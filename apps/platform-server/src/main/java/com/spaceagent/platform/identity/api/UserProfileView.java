package com.spaceagent.platform.identity.api;

import java.time.Instant;

/**
 * Public user profile query result.
 */
public record UserProfileView(
        String userId,
        String preferredTone,
        String timezone,
        String summary,
        Instant createdAt,
        Instant updatedAt) {
}
