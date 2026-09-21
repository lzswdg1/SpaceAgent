package com.spaceagent.platform.runtime.domain;

/**
 * Durable execution states for an {@link AgentRun}.
 */
public enum AgentRunState {
    QUEUED,
    IN_PROGRESS,
    WAITING_FOR_TOOL,
    WAITING_FOR_USER,
    CHECKPOINTING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED
}
