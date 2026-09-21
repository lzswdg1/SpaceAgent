package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;

/**
 * Typed, framework-independent reconstruction of a recoverable run.
 *
 * <p>This is a read projection assembled from the durable run ledger, checkpoints,
 * conversation snapshot references, and tool ledger. Serialization is an infrastructure
 * concern; the domain value object itself does not depend on a JSON provider.
 */
public record RecoveryResumeState(
        String agentRunId,
        String agentId,
        String configurationSnapshotId,
        String ownerId,
        String conversationId,
        String chatTaskId,
        String projectId,
        String projectDirectoryId,
        String workspaceId,
        String taskId,
        AgentRunState runState,
        String latestCheckpointId,
        int latestCheckpointSequence,
        String checkpointSnapshot,
        String currentRunStepId,
        String currentRunStepType,
        RunStepState currentRunStepState,
        String conversationContextSnapshotId,
        Integer conversationContextSnapshotVersion,
        String resumePhase,
        String resumeCursor,
        List<ToolExecutionRef> toolExecutions,
        Instant reconstructedAt) {

    public RecoveryResumeState {
        toolExecutions = List.copyOf(toolExecutions);
    }
}
