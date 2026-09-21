package com.spaceagent.platform.identity.domain;

public enum OrganizationCleanupJobState {
    PENDING,
    CLAIMED,
    RETRY,
    BLOCKED,
    COMPLETED
}
