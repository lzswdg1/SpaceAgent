package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.LocalWorkspaceBridgeState;
import java.time.Instant;

/** Path-free Bridge projection. */
public record LocalWorkspaceBridgeView(
        String id, String displayName, String deviceId, String rootHandle,
        String tokenPrefix, LocalWorkspaceBridgeState state, Instant lastSeenAt,
        Instant createdAt, Instant updatedAt, Instant revokedAt) { }
