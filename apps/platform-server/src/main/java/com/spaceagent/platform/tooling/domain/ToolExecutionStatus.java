package com.spaceagent.platform.tooling.domain;

/**
 * Lifecycle state of one tool execution ledger entry.
 */
public enum ToolExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN
}
