package com.spaceagent.platform.tooling.domain;

import java.util.List;

/**
 * Framework-neutral request for one isolated execution.
 */
public record SandboxExecutionRequest(
        String executionId,
        String agentRunId,
        String toolCallId,
        String workspaceRef,
        String taskRef,
        String tool,
        String command,
        List<String> arguments,
        int timeoutSeconds,
        SandboxResourcePolicy resourcePolicy,
        String environment,
        String inputBase64,
        String sourceRef) {

    public SandboxExecutionRequest {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public SandboxExecutionRequest(
            String executionId, String agentRunId, String toolCallId, String workspaceRef,
            String taskRef, String tool, String command, List<String> arguments,
            int timeoutSeconds, SandboxResourcePolicy resourcePolicy, String environment,
            String inputBase64) {
        this(executionId, agentRunId, toolCallId, workspaceRef, taskRef, tool, command,
                arguments, timeoutSeconds, resourcePolicy, environment, inputBase64, null);
    }

    public SandboxExecutionRequest(
            String executionId, String agentRunId, String toolCallId, String workspaceRef,
            String taskRef, String tool, String command, List<String> arguments,
            int timeoutSeconds, SandboxResourcePolicy resourcePolicy, String environment) {
        this(executionId, agentRunId, toolCallId, workspaceRef, taskRef, tool, command,
                arguments, timeoutSeconds, resourcePolicy, environment, null, null);
    }
}
