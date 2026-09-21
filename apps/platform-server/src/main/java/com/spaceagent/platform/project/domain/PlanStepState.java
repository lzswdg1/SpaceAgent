package com.spaceagent.platform.project.domain;

/** Execution state is persisted now; Runtime-owned transitions arrive in M18. */
public enum PlanStepState {
    PENDING,
    READY,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}
