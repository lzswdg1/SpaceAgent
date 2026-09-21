package com.spaceagent.platform.project.api;

/** Distinguishes a trustworthy child failure from an ambiguous Sandbox transport outcome. */
public class WorkspaceSandboxExecutionException extends RuntimeException {
    private final String safeCode;
    private final boolean ambiguous;

    public WorkspaceSandboxExecutionException(String safeCode, boolean ambiguous) {
        super(safeCode == null || safeCode.isBlank() ? "WORKSPACE_SANDBOX_FAILED" : safeCode);
        this.safeCode = safeCode == null || safeCode.isBlank()
                ? "WORKSPACE_SANDBOX_FAILED" : safeCode;
        this.ambiguous = ambiguous;
    }

    public String safeCode() { return safeCode; }
    public boolean ambiguous() { return ambiguous; }
}
