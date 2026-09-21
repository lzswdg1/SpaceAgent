package com.spaceagent.platform.automation.domain;

public enum AutomationExecutionState {
    PENDING_DISPATCH,
    APPROVAL_REQUIRED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REJECTED,
    EXPIRED,
    CANCELLED
}
