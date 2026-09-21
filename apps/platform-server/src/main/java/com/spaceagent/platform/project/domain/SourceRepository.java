package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A source-code repository owned by a {@link Project}.
 *
 * <p>This domain concept is distinct from a persistence {@code *Repository} or
 * {@code *Store} port. It describes where source code lives on disk and remotely.
 */
public record SourceRepository(
        String id,
        String projectId,
        String tenantId,
        String mcpConnectionId,
        String mcpInvocationId,
        String workspaceBridgeId,
        String providerRepositoryId,
        String displayName,
        String remoteUrl,
        String localRootHandle,
        String defaultBranch,
        SourceRepositoryType type,
        SourceRepositoryState state,
        SourceRepositoryVisibility visibility,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String materializationSessionId,
        String snapshotRef,
        String manifestSha256,
        String contentSha256,
        String finalizeRequestId) {

    public SourceRepository {
        id = requireText(id, "id");
        projectId = requireText(projectId, "projectId");
        tenantId = requireText(tenantId, "tenantId");
        displayName = requireText(displayName, "displayName");
        defaultBranch = requireText(defaultBranch, "defaultBranch");
        type = Objects.requireNonNull(type, "type");
        state = Objects.requireNonNull(state, "state");
        visibility = Objects.requireNonNull(visibility, "visibility");
        createdBy = requireText(createdBy, "createdBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        mcpConnectionId = normalize(mcpConnectionId);
        mcpInvocationId = normalize(mcpInvocationId);
        workspaceBridgeId = normalize(workspaceBridgeId);
        providerRepositoryId = normalize(providerRepositoryId);
        remoteUrl = normalize(remoteUrl);
        localRootHandle = normalize(localRootHandle);
        materializationSessionId = normalize(materializationSessionId);
        snapshotRef = normalize(snapshotRef);
        manifestSha256 = normalize(manifestSha256);
        contentSha256 = normalize(contentSha256);
        finalizeRequestId = normalize(finalizeRequestId);
        if (type == SourceRepositoryType.LOCAL) {
            requireOpaqueRootHandle(localRootHandle);
            if (workspaceBridgeId == null || remoteUrl != null || mcpConnectionId != null
                    || mcpInvocationId != null) {
                throw new IllegalArgumentException(
                        "LOCAL source requires a Bridge and no remote URL");
            }
        }
        if (type == SourceRepositoryType.GITHUB
                && (providerRepositoryId == null || remoteUrl == null)) {
            throw new IllegalArgumentException(
                    "GITHUB source requires provider identity and remote URL");
        }
        if (type == SourceRepositoryType.GITHUB
                && (mcpConnectionId == null) != (mcpInvocationId == null)) {
            throw new IllegalArgumentException(
                    "GITHUB MCP source requires Connection and Invocation provenance together");
        }
        if (type == SourceRepositoryType.MANAGED_SNAPSHOT) {
            requireText(materializationSessionId, "materializationSessionId");
            if (snapshotRef == null || !snapshotRef.matches("sources/[0-9a-f-]{36}")
                    || manifestSha256 == null || !manifestSha256.matches("sha256:[0-9a-f]{64}")
                    || contentSha256 == null || !contentSha256.matches("sha256:[0-9a-f]{64}")
                    || finalizeRequestId == null || workspaceBridgeId != null || localRootHandle != null
                    || remoteUrl != null || mcpConnectionId != null || mcpInvocationId != null) {
                throw new IllegalArgumentException("MANAGED_SNAPSHOT source evidence is invalid");
            }
        } else if (materializationSessionId != null || snapshotRef != null || manifestSha256 != null
                || contentSha256 != null || finalizeRequestId != null) {
            throw new IllegalArgumentException("Only MANAGED_SNAPSHOT may contain snapshot evidence");
        }
    }

    public SourceRepository(
            String id, String projectId, String tenantId, String mcpConnectionId,
            String mcpInvocationId, String workspaceBridgeId, String providerRepositoryId,
            String displayName, String remoteUrl, String localRootHandle, String defaultBranch,
            SourceRepositoryType type, SourceRepositoryState state,
            SourceRepositoryVisibility visibility, String createdBy, Instant createdAt, Instant updatedAt) {
        this(id, projectId, tenantId, mcpConnectionId, mcpInvocationId, workspaceBridgeId,
                providerRepositoryId, displayName, remoteUrl, localRootHandle, defaultBranch, type,
                state, visibility, createdBy, createdAt, updatedAt, null, null, null, null, null);
    }

    /** Compatibility constructor for the pre-M19 reference-only domain contract. */
    public SourceRepository(
            String id,
            String projectId,
            String remoteUrl,
            String localPath,
            String defaultBranch,
            SourceRepositoryType type,
            SourceRepositoryState state,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, "legacy", null, null, null, "legacy:" + id, id, remoteUrl, localPath,
                defaultBranch, type, state, SourceRepositoryVisibility.PRIVATE,
                "legacy", createdAt, updatedAt);
    }

    public SourceRepository archive(Instant at) {
        return new SourceRepository(
                id, projectId, tenantId, mcpConnectionId, mcpInvocationId,
                workspaceBridgeId,
                providerRepositoryId, displayName, remoteUrl, localRootHandle,
                defaultBranch, type, SourceRepositoryState.ARCHIVED, visibility,
                createdBy, createdAt, Objects.requireNonNull(at, "at"), materializationSessionId,
                snapshotRef, manifestSha256, contentSha256, finalizeRequestId);
    }

    public SourceRepository ready(Instant at) {
        if (state != SourceRepositoryState.PROVISIONING) {
            throw new IllegalStateException("Only PROVISIONING source can become READY");
        }
        return new SourceRepository(id, projectId, tenantId, mcpConnectionId, mcpInvocationId,
                workspaceBridgeId, providerRepositoryId, displayName, remoteUrl, localRootHandle,
                defaultBranch, type, SourceRepositoryState.READY, visibility, createdBy, createdAt,
                Objects.requireNonNull(at, "at"), materializationSessionId, snapshotRef,
                manifestSha256, contentSha256, finalizeRequestId);
    }

    public static void requireOpaqueRootHandle(String value) {
        String handle = requireText(value, "localRootHandle");
        if (handle.length() < 8 || handle.length() > 128
                || handle.contains("/") || handle.contains("\\")
                || handle.contains(":") || handle.contains("..")
                || !handle.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "localRootHandle must be an opaque non-path identifier");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
