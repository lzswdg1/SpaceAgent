package com.spaceagent.platform.runtime.domain;

/**
 * Stable test status captured in a {@link HandoffSnapshot}.
 */
public enum HandoffTestStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    FAILED,
    PARTIAL
}
