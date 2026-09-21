package com.spaceagent.platform.tooling.domain;

/**
 * Domain port for isolated code/tool execution. The Python sandbox-worker is one
 * infrastructure implementation; the Java ToolExecutionLedger wraps the returned result.
 */
public interface SandboxExecutionGateway {

    SandboxExecutionResponse execute(SandboxExecutionRequest request);
}
