package com.spaceagent.platform.runtime.domain;

/**
 * Lifecycle state of a {@link RunStep}.
 */
public enum RunStepState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED
}
