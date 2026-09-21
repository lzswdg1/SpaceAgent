package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/** Metadata for a bounded staged byte range. The relative path is untrusted metadata, never a host path. */
public record ProjectLocalMaterializationChunk(
        String id,
        String sessionId,
        String requestId,
        String relativePath,
        long offset,
        long contentLength,
        String contentSha256,
        String stagingKey,
        ProjectLocalMaterializationChunkState state,
        String blockedCode,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    public static final long MAX_CONTENT_LENGTH = 8L * 1024 * 1024;

    public ProjectLocalMaterializationChunk {
        requireText(id, "id", 36);
        requireText(sessionId, "sessionId", 36);
        requireText(requestId, "requestId", 200);
        requireText(relativePath, "relativePath", 1024);
        if (offset < 0 || contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("chunk range is invalid");
        }
        requireSha256(contentSha256, "contentSha256");
        if (stagingKey == null || !stagingKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("stagingKey is invalid");
        }
        state = Objects.requireNonNull(state, "state");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if ((state == ProjectLocalMaterializationChunkState.STORED && blockedCode != null)
                || (state == ProjectLocalMaterializationChunkState.BLOCKED
                && (blockedCode == null || blockedCode.isBlank()))) {
            throw new IllegalArgumentException("chunk state evidence is invalid");
        }
    }

    public static ProjectLocalMaterializationChunk stored(
            String id, String sessionId, String requestId, String relativePath, long offset,
            long contentLength, String contentSha256, String stagingKey, Instant now) {
        return new ProjectLocalMaterializationChunk(
                id, sessionId, requestId, relativePath, offset, contentLength, contentSha256, stagingKey,
                ProjectLocalMaterializationChunkState.STORED, null, 1L, now, now);
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
