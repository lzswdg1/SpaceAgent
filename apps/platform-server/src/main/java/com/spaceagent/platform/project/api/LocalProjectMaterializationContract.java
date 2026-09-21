package com.spaceagent.platform.project.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Public, path-free local materialization messages. Bytes and Bridge credentials are transport-only
 * inputs and must never be stored in these values.
 */
public final class LocalProjectMaterializationContract {
    public static final String VERSION = "local-materialization/v1";

    private LocalProjectMaterializationContract() { }

    public record StartSessionRequest(
            String contractVersion,
            String requestId,
            String tenantId,
            String ownerUserId,
            String projectId,
            String bridgeId) {
        public StartSessionRequest {
            requireVersion(contractVersion);
            requireText(requestId, "requestId", 200);
            requireText(tenantId, "tenantId", 200);
            requireText(ownerUserId, "ownerUserId", 200);
            requireText(projectId, "projectId", 200);
            requireText(bridgeId, "bridgeId", 200);
        }
    }

    /** Session reference contains verified server bindings only; it does not expose a root handle. */
    public record SessionReference(
            String contractVersion,
            String sessionId,
            String bridgeId,
            String deviceId,
            Instant expiresAt) {
        public SessionReference {
            requireVersion(contractVersion);
            requireText(sessionId, "sessionId", 200);
            requireText(bridgeId, "bridgeId", 200);
            requireText(deviceId, "deviceId", 160);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Structural manifest declaration; U04 owns canonical path and filesystem validation. */
    public record ManifestDeclaration(
            String contractVersion,
            String sessionId,
            String requestId,
            String manifestSha256,
            long totalBytes,
            int fileCount,
            List<FileEntry> entries) {
        public ManifestDeclaration {
            requireVersion(contractVersion);
            requireText(sessionId, "sessionId", 200);
            requireText(requestId, "requestId", 200);
            requireSha256(manifestSha256, "manifestSha256");
            if (totalBytes < 0 || fileCount < 0) {
                throw new IllegalArgumentException("manifest size is invalid");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (entries.size() != fileCount) {
                throw new IllegalArgumentException("fileCount must match entries");
            }
            Set<String> paths = new HashSet<>();
            for (FileEntry entry : entries) {
                if (!paths.add(entry.relativePath())) {
                    throw new IllegalArgumentException("manifest entries must be unique");
                }
            }
        }
    }

    /** Only regular-file metadata is expressible; links, submodules and special files are rejected. */
    public record FileEntry(String relativePath, long contentLength, String contentSha256) {
        public FileEntry {
            requireText(relativePath, "relativePath", 1024);
            if (contentLength < 0) {
                throw new IllegalArgumentException("contentLength is invalid");
            }
            requireSha256(contentSha256, "contentSha256");
        }
    }

    /** Chunk bytes belong to a bounded future streaming body, not this durable/JSON descriptor. */
    public record ChunkDescriptor(
            String contractVersion,
            String sessionId,
            String requestId,
            String relativePath,
            long offset,
            long contentLength,
            String contentSha256) {
        public ChunkDescriptor {
            requireVersion(contractVersion);
            requireText(sessionId, "sessionId", 200);
            requireText(requestId, "requestId", 200);
            requireText(relativePath, "relativePath", 1024);
            if (offset < 0 || contentLength <= 0) {
                throw new IllegalArgumentException("chunk range is invalid");
            }
            requireSha256(contentSha256, "contentSha256");
        }
    }

    public record FinalizeRequest(
            String contractVersion,
            String sessionId,
            String requestId,
            String manifestSha256) {
        public FinalizeRequest {
            requireVersion(contractVersion);
            requireText(sessionId, "sessionId", 200);
            requireText(requestId, "requestId", 200);
            requireSha256(manifestSha256, "manifestSha256");
        }
    }

    private static void requireVersion(String value) {
        if (!VERSION.equals(value)) {
            throw new IllegalArgumentException("contractVersion is unsupported");
        }
    }

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 digest");
        }
    }
}
