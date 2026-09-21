package com.spaceagent.platform.runtime.domain;

/** Domain failures whose handling must remain explicit at the Runtime boundary. */
public enum ProjectPlanExecutionControlFailure {
    UNKNOWN_EFFECT("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT"),
    AMBIGUOUS_EFFECT("PROJECT_PLAN_EXECUTION_AMBIGUOUS_EFFECT"),
    LEASE_LOST("PROJECT_PLAN_EXECUTION_LEASE_LOST"),
    STALE_REVISION("PROJECT_PLAN_EXECUTION_STALE_REVISION"),
    INVALID_TRANSITION("PROJECT_PLAN_EXECUTION_INVALID_TRANSITION"),
    TERMINAL_STATE("PROJECT_PLAN_EXECUTION_TERMINAL_STATE"),
    RECONCILIATION_REQUIRED("PROJECT_PLAN_EXECUTION_RECONCILIATION_REQUIRED"),
    REASON_REQUIRED("PROJECT_PLAN_EXECUTION_REASON_REQUIRED");

    private final String safeErrorCode;

    ProjectPlanExecutionControlFailure(String safeErrorCode) {
        this.safeErrorCode = safeErrorCode;
    }

    public String safeErrorCode() {
        return safeErrorCode;
    }
}
