package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable Knowledge-owned evidence for one document Workspace byte mutation. */
public record DocumentWorkspaceOperation(
        String id, String tenantId, String workspaceId, String actorUserId,
        String agentRunId, String runStepId,
        String toolCallId, String idempotencyKey, String inputHash,
        Type type, String path, long requestedBytes, long previousBytes, boolean previousFilePresent,
        long reservedFiles, long reservedBytes, State state,
        Long resultBytes, String resultHash, String safeErrorCode,
        long revision, Instant createdAt, Instant updatedAt, Instant completedAt) {

    public DocumentWorkspaceOperation {
        require(id); require(tenantId); require(workspaceId); require(actorUserId); require(agentRunId); require(runStepId);
        require(toolCallId); require(idempotencyKey); require(inputHash); require(path);
        Objects.requireNonNull(type); Objects.requireNonNull(state);
        if (!inputHash.matches("sha256:[0-9a-f]{64}") || path.startsWith("/")
                || path.contains("\\") || path.contains("\0")
                || java.util.Arrays.asList(path.split("/", -1)).stream()
                .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
                || requestedBytes < 0 || previousBytes < 0 || reservedFiles < 0
                || reservedFiles > 1 || reservedBytes < 0 || revision <= 0
                || (resultHash != null && !resultHash.matches("sha256:[0-9a-f]{64}"))
                || ((state == State.SUCCEEDED || state == State.FAILED) != (completedAt != null))) {
            throw new IllegalArgumentException("Document Workspace operation is invalid");
        }
    }

    public boolean sameRequest(String callId, String key, String hash, Type requestedType, String requestedPath) {
        return toolCallId.equals(callId) && idempotencyKey.equals(key) && inputHash.equals(hash)
                && type == requestedType && path.equals(requestedPath);
    }

    public enum Type { WRITE, DELETE }
    public enum State { PENDING, UNKNOWN, SUCCEEDED, FAILED }
    private static void require(String value) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new IllegalArgumentException("Document Workspace operation field is invalid");
        }
    }
}
