package com.spaceagent.platform.tooling.api;

/**
 * Public result of a sandbox tool execution, derived from the durable ledger.
 */
public record SandboxToolExecutionView(
        String toolCallId,
        String status,
        String result,
        String resultRef,
        String error) {
}
