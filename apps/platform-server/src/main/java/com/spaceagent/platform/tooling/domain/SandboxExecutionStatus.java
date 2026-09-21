package com.spaceagent.platform.tooling.domain;

/**
 * Terminal status returned by the sandbox worker.
 */
public enum SandboxExecutionStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    OUTPUT_LIMIT_EXCEEDED,
    REJECTED
}
