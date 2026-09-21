package com.spaceagent.platform.tooling.domain;

/**
 * Indicates the sandbox worker could not be reached. ToolExecutionLedger remains
 * authoritative and the entry must be reconciled, not blindly re-executed.
 */
public class SandboxExecutionUnavailableException extends RuntimeException {

    public SandboxExecutionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public SandboxExecutionUnavailableException(String message) {
        super(message);
    }
}
