package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentWorkspaceRepository {
    Instant currentTime();
    void insert(DocumentWorkspace workspace, String createIdempotencyKey);
    Optional<DocumentWorkspace> findByCreateKey(String tenantId, String ownerId, String key);
    Optional<DocumentWorkspace> findById(String tenantId, String id);
    List<DocumentWorkspace> list(String tenantId);
    Optional<DocumentWorkspace> update(DocumentWorkspace workspace, long expectedRevision);
    MutationClaim claimMutation(MutationRequest request);
    Optional<MutationResult> completeMutation(String tenantId, String operationId,
            long actualBytes, String resultHash, Instant at);
    Optional<MutationResult> failMutation(String tenantId, String operationId,
            String safeErrorCode, Instant at);
    Optional<MutationResult> markMutationUnknown(String tenantId, String operationId,
            String safeErrorCode, Instant at);
    Optional<DocumentWorkspaceOperation> findMutation(
            String tenantId, String agentRunId, String toolCallId);

    record MutationRequest(String operationId, String tenantId, String workspaceId,
            String actorUserId, String agentRunId, String runStepId,
            String toolCallId, String idempotencyKey, String inputHash,
            DocumentWorkspaceOperation.Type type, String path, long requestedBytes, Instant at) {}
    enum ClaimType { CLAIMED, REPLAY, UNKNOWN, FAILED, BUSY, CONFLICT }
    record MutationClaim(ClaimType type, DocumentWorkspace workspace,
            DocumentWorkspaceOperation operation) {}
    record MutationResult(DocumentWorkspace workspace, DocumentWorkspaceOperation operation) {}
}
