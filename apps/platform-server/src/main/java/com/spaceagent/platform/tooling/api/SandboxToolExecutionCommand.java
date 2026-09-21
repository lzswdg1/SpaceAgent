package com.spaceagent.platform.tooling.api;

import java.util.List;

/**
 * Public command for executing a tool through the sandbox boundary.
 *
 * <p>The command already contains the resolved executable and arguments; the caller
 * does not need to know whether execution is local or delegated to the Python worker.
 */
public record SandboxToolExecutionCommand(
        String agentRunId,
        String runStepId,
        String toolName,
        String toolCallId,
        String idempotencyKey,
        String command,
        List<String> arguments,
        String workspaceRef,
        String taskRef,
        int timeoutSeconds,
        String inputBase64) {

    public SandboxToolExecutionCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(runStepId, "runStepId");
        requireNonBlank(toolName, "toolName");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(command, "command");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
    }

    public SandboxToolExecutionCommand(String agentRunId,String runStepId,String toolName,
            String toolCallId,String idempotencyKey,String command,List<String> arguments,
            String workspaceRef,String taskRef,int timeoutSeconds){
        this(agentRunId,runStepId,toolName,toolCallId,idempotencyKey,command,arguments,
                workspaceRef,taskRef,timeoutSeconds,null);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
