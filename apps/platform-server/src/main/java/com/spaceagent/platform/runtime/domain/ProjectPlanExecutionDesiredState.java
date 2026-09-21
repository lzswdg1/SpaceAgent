package com.spaceagent.platform.runtime.domain;

/** User-requested control target for a Project TaskPlan execution. */
public enum ProjectPlanExecutionDesiredState {
    RUNNING,
    PAUSED,
    CANCELLED
}
