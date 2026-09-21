package com.spaceagent.platform.tooling.api;

import java.util.List;

/**
 * Internal compute-only sandbox boundary for callers that already own an effect ledger, or for
 * read-only Project recovery capture whose immutable Runtime snapshot becomes durable evidence.
 * This API persists nothing and must never be exposed as a public arbitrary-execution HTTP API.
 */
public interface SandboxComputeApplicationApi {
    ComputeResult execute(ComputeCommand command);

    record ComputeCommand(
            String agentRunId,
            String toolCallId,
            String workspaceRef,
            String taskRef,
            String tool,
            String executable,
            List<String> arguments,
            int timeoutSeconds,
            String inputBase64,
            String sourceRef) {
        public ComputeCommand {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        public ComputeCommand(
                String agentRunId, String toolCallId, String workspaceRef, String taskRef,
                String tool, String executable, List<String> arguments, int timeoutSeconds,
                String inputBase64) {
            this(agentRunId, toolCallId, workspaceRef, taskRef, tool, executable, arguments,
                    timeoutSeconds, inputBase64, null);
        }

        public ComputeCommand(
                String agentRunId, String toolCallId, String workspaceRef, String taskRef,
                String tool, String executable, List<String> arguments, int timeoutSeconds) {
            this(agentRunId, toolCallId, workspaceRef, taskRef, tool, executable, arguments,
                    timeoutSeconds, null, null);
        }
    }

    record ComputeResult(
            String status,
            int exitStatus,
            String stdout,
            String stderr,
            boolean timedOut,
            long wallTimeMs,
            long outputBytes,
            String error) {
    }
}
