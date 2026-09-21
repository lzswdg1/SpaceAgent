package com.spaceagent.platform.runtime.domain;

/** Framework-independent failure raised by an invalid execution control transition. */
public final class ProjectPlanExecutionControlException extends IllegalStateException {
    private final ProjectPlanExecutionControlFailure failure;

    public ProjectPlanExecutionControlException(ProjectPlanExecutionControlFailure failure) {
        super(failure.safeErrorCode());
        this.failure = failure;
    }

    public ProjectPlanExecutionControlFailure failure() {
        return failure;
    }

    public String safeErrorCode() {
        return failure.safeErrorCode();
    }
}
