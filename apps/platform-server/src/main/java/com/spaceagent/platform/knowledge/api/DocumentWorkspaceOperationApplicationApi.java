package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceOperation;

public interface DocumentWorkspaceOperationApplicationApi {
    DocumentWorkspaceApplicationApi.WorkspaceView requireAccessible(ScopeQuery query);
    ClaimView claim(ClaimCommand command);
    OperationView complete(CompleteCommand command);
    OperationView fail(FailCommand command);
    OperationView markUnknown(FailCommand command);
    OperationView findMutation(FindMutationQuery query);

    record ScopeQuery(String tenantId, String actorUserId, String workspaceId, boolean writable) {}
    record ClaimCommand(String tenantId, String actorUserId, String workspaceId,
            String agentRunId, String runStepId, String toolCallId, String idempotencyKey, String inputHash,
            DocumentWorkspaceOperation.Type type, String path, long requestedBytes) {}
    record CompleteCommand(String tenantId, String operationId, long actualBytes, String resultHash) {}
    record FailCommand(String tenantId, String operationId, String safeErrorCode) {}
    record FindMutationQuery(String tenantId, String actorUserId, String agentRunId, String toolCallId) {}
    record ClaimView(String decision, OperationView operation) {}
    record OperationView(String id, String workspaceId, String agentRunId, String runStepId,
            String toolCallId, String inputHash,
            String type, String path, long requestedBytes, long previousBytes, boolean previousFilePresent,
            long reservedFiles, long reservedBytes, String state, Long resultBytes,
            String resultHash, String safeErrorCode, long revision) {}
}
