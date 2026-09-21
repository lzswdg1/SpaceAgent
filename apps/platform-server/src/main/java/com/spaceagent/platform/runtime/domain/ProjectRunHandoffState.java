package com.spaceagent.platform.runtime.domain;

public enum ProjectRunHandoffState {
    PENDING,
    ACTIVE,
    READY_TO_FINALIZE,
    FINALIZING,
    COMPLETED,
    BLOCKED
}
