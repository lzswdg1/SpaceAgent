package com.spaceagent.platform.identity.domain;

public enum UserCleanupJobState {
    PENDING,
    CLAIMED,
    RETRY,
    BLOCKED,
    COMPLETED
}
