package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/** One user/device/root capability. It never contains an absolute filesystem path. */
public record LocalWorkspaceBridge(
        String id,
        String tenantId,
        String ownerId,
        String displayName,
        String deviceId,
        String rootHandle,
        String tokenHash,
        String tokenPrefix,
        LocalWorkspaceBridgeState state,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt) {

    public LocalWorkspaceBridge {
        requireText(id, "id");
        requireText(tenantId, "tenantId");
        requireText(ownerId, "ownerId");
        requireText(displayName, "displayName");
        requireText(deviceId, "deviceId");
        SourceRepository.requireOpaqueRootHandle(rootHandle);
        if (tokenHash == null || tokenHash.length() != 64) {
            throw new IllegalArgumentException("tokenHash must be SHA-256 hex");
        }
        requireText(tokenPrefix, "tokenPrefix");
        state = Objects.requireNonNull(state, "state");
        lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public LocalWorkspaceBridge heartbeat(Instant at) {
        if (state != LocalWorkspaceBridgeState.ACTIVE) {
            throw new IllegalStateException("Revoked Bridge cannot heartbeat");
        }
        return new LocalWorkspaceBridge(
                id, tenantId, ownerId, displayName, deviceId, rootHandle,
                tokenHash, tokenPrefix, state, at, createdAt, at, revokedAt);
    }

    public LocalWorkspaceBridge revoke(Instant at) {
        return new LocalWorkspaceBridge(
                id, tenantId, ownerId, displayName, deviceId, rootHandle,
                tokenHash, tokenPrefix, LocalWorkspaceBridgeState.REVOKED,
                lastSeenAt, createdAt, at, at);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
