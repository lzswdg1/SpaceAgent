package com.spaceagent.platform.tooling.domain;

public enum McpConnectionState {
    PENDING_AUTH,
    PENDING_VALIDATION,
    ACTIVE,
    DEGRADED,
    ERROR,
    REVOKED
}
