package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * A durable tenant-scoped user profile.
 */
public record UserProfile(
        String userId,
        String preferredTone,
        String timezone,
        String summary,
        Instant createdAt,
        Instant updatedAt) {

    public static UserProfile defaultProfile(String userId, Instant now) {
        return new UserProfile(userId, "WARM", "Asia/Shanghai", "", now, now);
    }
}
