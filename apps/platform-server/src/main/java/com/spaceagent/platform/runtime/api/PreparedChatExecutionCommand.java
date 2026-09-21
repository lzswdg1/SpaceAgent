package com.spaceagent.platform.runtime.api;

/** Executes Chat on an existing, already fenced/resumed AgentRun. */
public record PreparedChatExecutionCommand(
        String tenantId,
        String userId,
        String conversationId,
        String agentId,
        String configurationSnapshotId,
        String agentRunId,
        String message) {}
