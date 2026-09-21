package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable Project-owned materialization scope. It holds only an opaque Bridge root handle, never a
 * local filesystem path, Bridge credential, chunk bytes, Source snapshot or Workspace.
 */
public record ProjectLocalMaterializationSession(
        String id,
        String tenantId,
        String ownerUserId,
        String projectId,
        String bridgeId,
        String bridgeDeviceId,
        String bridgeRootHandle,
        String requestId,
        String manifestSha256,
        ProjectLocalMaterializationSessionState state,
        String blockedCode,
        long revision,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public ProjectLocalMaterializationSession {
        requireText(id, "id", 200);
        requireText(tenantId, "tenantId", 36);
        requireText(ownerUserId, "ownerUserId", 36);
        requireText(projectId, "projectId", 36);
        requireText(bridgeId, "bridgeId", 36);
        requireText(bridgeDeviceId, "bridgeDeviceId", 160);
        requireOpaqueRootHandle(bridgeRootHandle);
        requireText(requestId, "requestId", 200);
        state = Objects.requireNonNull(state, "state");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (manifestSha256 != null) {
            requireSha256(manifestSha256, "manifestSha256");
        }
        validateOutcome(state, manifestSha256, blockedCode, completedAt);
    }

    public static ProjectLocalMaterializationSession open(
            String id,
            String tenantId,
            String ownerUserId,
            String projectId,
            String bridgeId,
            String bridgeDeviceId,
            String bridgeRootHandle,
            String requestId,
            Instant expiresAt,
            Instant now) {
        return new ProjectLocalMaterializationSession(
                id, tenantId, ownerUserId, projectId, bridgeId, bridgeDeviceId, bridgeRootHandle,
                requestId, null, ProjectLocalMaterializationSessionState.OPEN, null, 1L, expiresAt,
                now, now, null);
    }

    public ProjectLocalMaterializationSession beginUpload(String nextManifestSha256, Instant now) {
        requireActive(ProjectLocalMaterializationSessionState.OPEN, now, "begin upload");
        requireSha256(nextManifestSha256, "manifestSha256");
        return transition(ProjectLocalMaterializationSessionState.UPLOADING, nextManifestSha256, null, now, null);
    }

    public ProjectLocalMaterializationSession verify(Instant now) {
        requireState(ProjectLocalMaterializationSessionState.UPLOADING, "verify");
        return transition(ProjectLocalMaterializationSessionState.VERIFIED, manifestSha256, null, now, null);
    }

    public ProjectLocalMaterializationSession materialize(Instant now) {
        requireState(ProjectLocalMaterializationSessionState.VERIFIED, "materialize");
        return transition(ProjectLocalMaterializationSessionState.MATERIALIZED, manifestSha256, null, now, now);
    }

    public ProjectLocalMaterializationSession expire(Instant now) {
        if (isTerminal()) {
            return this;
        }
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("materialization session has not expired");
        }
        return transition(ProjectLocalMaterializationSessionState.EXPIRED, manifestSha256, null, now, now);
    }

    public ProjectLocalMaterializationSession cancel(Instant now) {
        if (isTerminal()) {
            return this;
        }
        return transition(ProjectLocalMaterializationSessionState.CANCELLED, manifestSha256, null, now, now);
    }

    /** Unknown byte or finalize effects are fail-closed and cannot return to an active state. */
    public ProjectLocalMaterializationSession blockUnknown(String safeCode, Instant now) {
        requireText(safeCode, "safeCode", 120);
        if (state == ProjectLocalMaterializationSessionState.BLOCKED) {
            return this;
        }
        if (isTerminal()) {
            throw new IllegalStateException("terminal materialization session cannot become blocked");
        }
        return transition(ProjectLocalMaterializationSessionState.BLOCKED, manifestSha256, safeCode, now, null);
    }

    public boolean isTerminal() {
        return state == ProjectLocalMaterializationSessionState.MATERIALIZED
                || state == ProjectLocalMaterializationSessionState.EXPIRED
                || state == ProjectLocalMaterializationSessionState.CANCELLED;
    }

    private ProjectLocalMaterializationSession transition(
            ProjectLocalMaterializationSessionState nextState,
            String nextManifestSha256,
            String nextBlockedCode,
            Instant now,
            Instant nextCompletedAt) {
        return new ProjectLocalMaterializationSession(
                id, tenantId, ownerUserId, projectId, bridgeId, bridgeDeviceId, bridgeRootHandle,
                requestId, nextManifestSha256, nextState, nextBlockedCode, revision + 1, expiresAt,
                createdAt, Objects.requireNonNull(now, "now"), nextCompletedAt);
    }

    private void requireActive(ProjectLocalMaterializationSessionState expected, Instant now, String action) {
        requireState(expected, action);
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("expired materialization session cannot " + action);
        }
    }

    private void requireState(ProjectLocalMaterializationSessionState expected, String action) {
        if (state != expected) {
            throw new IllegalStateException("cannot " + action + " from " + state);
        }
    }

    private static void validateOutcome(
            ProjectLocalMaterializationSessionState state,
            String manifestSha256,
            String blockedCode,
            Instant completedAt) {
        boolean manifestRequired = state == ProjectLocalMaterializationSessionState.UPLOADING
                || state == ProjectLocalMaterializationSessionState.VERIFIED
                || state == ProjectLocalMaterializationSessionState.MATERIALIZED;
        boolean completedRequired = state == ProjectLocalMaterializationSessionState.MATERIALIZED
                || state == ProjectLocalMaterializationSessionState.EXPIRED
                || state == ProjectLocalMaterializationSessionState.CANCELLED;
        if ((manifestRequired && manifestSha256 == null)
                || (state == ProjectLocalMaterializationSessionState.OPEN && manifestSha256 != null)
                || (state == ProjectLocalMaterializationSessionState.BLOCKED && (blockedCode == null || blockedCode.isBlank()))
                || (state != ProjectLocalMaterializationSessionState.BLOCKED && blockedCode != null)
                || (completedRequired != (completedAt != null))) {
            throw new IllegalArgumentException("materialization session state evidence is invalid");
        }
    }

    private static void requireOpaqueRootHandle(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("bridgeRootHandle must be opaque");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
    }

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
