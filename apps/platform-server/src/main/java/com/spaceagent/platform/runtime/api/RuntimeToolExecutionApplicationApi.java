package com.spaceagent.platform.runtime.api;

public interface RuntimeToolExecutionApplicationApi {
    RuntimeToolResult execute(ExecuteRuntimeToolCommand command);

    record ExecuteRuntimeToolCommand(
            String userId,
            String agentRunId,
            String runStepId,
            String toolCallId,
            String toolId,
            String argumentsJson) {
    }

    record RuntimeToolResult(
            String toolCallId,
            String toolId,
            String status,
            String result,
            String resultRef,
            String error,
            Long ledgerRevision) {

        public RuntimeToolResult(
                String toolCallId, String toolId, String status,
                String result, String resultRef, String error) {
            this(toolCallId, toolId, status, result, resultRef, error, null);
        }
    }
}
