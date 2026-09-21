package com.spaceagent.platform.project.domain;

/**
 * Task lifecycle states owned by the project module.
 */
public enum TaskState {
    PENDING,
    READY,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
