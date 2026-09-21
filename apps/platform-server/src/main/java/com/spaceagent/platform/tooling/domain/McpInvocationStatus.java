package com.spaceagent.platform.tooling.domain;

/** Durable outcome of one control-plane MCP invocation. */
public enum McpInvocationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
