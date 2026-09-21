package com.spaceagent.platform.runtime.domain;

/**
 * Lifecycle state of a recovery attempt.
 */
public enum RecoveryState {
    REQUESTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
