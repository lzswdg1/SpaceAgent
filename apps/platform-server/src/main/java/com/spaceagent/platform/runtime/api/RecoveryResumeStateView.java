package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunStepState;

import java.time.Instant;
import java.util.List;

/**
 * Public reconstruction of a durable recovery resume state.
 */
public record RecoveryResumeStateView(
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
        List<ToolExecutionRefView> toolExecutions,
        Instant reconstructedAt) {

    public RecoveryResumeStateView {
        toolExecutions = List.copyOf(toolExecutions);
    }
}
