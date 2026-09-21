package com.spaceagent.platform.project.api;

public record HeartbeatLocalWorkspaceBridgeCommand(
        String tenantId, String userId, String bridgeId, String rawToken) { }
