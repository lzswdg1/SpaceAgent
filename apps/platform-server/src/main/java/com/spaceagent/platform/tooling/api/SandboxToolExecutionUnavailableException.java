package com.spaceagent.platform.tooling.api;

/**
 * Public API exception indicating the sandbox worker could not be reached.
 *
 * <p>Runtime consumes this public boundary type rather than the tooling domain
 * implementation exception. The ToolExecutionLedger remains authoritative and the
 * entry must be reconciled, not blindly re-executed.
 */
public class SandboxToolExecutionUnavailableException extends RuntimeException {

    public SandboxToolExecutionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
