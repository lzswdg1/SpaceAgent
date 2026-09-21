package com.spaceagent.platform.project.domain;

public enum ProjectIntakeState {
    PENDING,
    RUNNING,
    PROPOSED,
    CONFIRMED,
    REJECTED,
    FAILED,
    BLOCKED;

    public boolean terminal() {
        return this == CONFIRMED || this == REJECTED || this == FAILED || this == BLOCKED;
    }
}
