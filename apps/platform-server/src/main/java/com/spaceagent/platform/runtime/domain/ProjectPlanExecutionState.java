package com.spaceagent.platform.runtime.domain;

/** Execution lifecycle for a Project TaskPlan run. */
public enum ProjectPlanExecutionState {
    READY,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    FAILED,
    BLOCKED
}
