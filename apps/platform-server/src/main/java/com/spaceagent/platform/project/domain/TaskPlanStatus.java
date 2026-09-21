package com.spaceagent.platform.project.domain;

public enum TaskPlanStatus {
    DRAFT,
    PROPOSED,
    APPROVED,
    ACTIVE,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
