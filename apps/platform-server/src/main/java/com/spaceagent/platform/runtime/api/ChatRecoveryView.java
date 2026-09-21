package com.spaceagent.platform.runtime.api;

public record ChatRecoveryView(
        String agentRunId,
        String recoveryId,
        String state,
        String checkpointId,
        boolean resumed,
        boolean continuationScheduled) {

    public ChatRecoveryView(
            String agentRunId,
            String recoveryId,
            String state,
            String checkpointId,
            boolean resumed) {
        this(agentRunId, recoveryId, state, checkpointId, resumed, resumed);
    }
}
