package com.spaceagent.platform.runtime.domain;

/**
 * Lifecycle state of a runtime handoff.
 */
public enum HandoffState {
    PENDING,
    ACCEPTED,
    COMPLETED,
    FAILED
}
