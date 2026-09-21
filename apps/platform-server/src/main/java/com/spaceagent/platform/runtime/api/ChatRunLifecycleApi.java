package com.spaceagent.platform.runtime.api;

/** User-facing projection: no checkpoints, Tool arguments, file contents or internal refs. */
public interface ChatRunLifecycleApi {
    Status latest(String tenantId,String userId,String conversationId);
    Status get(String tenantId,String userId,String runId);
    Status cancel(String tenantId,String userId,String runId);
    record Status(String agentRunId,String conversationId,String executionState,long revision,
            String approvalId,String toolCallId,String toolName,Long toolRevision,
            String rootTaskId,String taskPlanId,boolean partial,int uncertainCalls,String responseUpdatedAt) { }
}
