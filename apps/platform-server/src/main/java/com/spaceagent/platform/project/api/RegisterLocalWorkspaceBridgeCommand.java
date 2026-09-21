package com.spaceagent.platform.project.api;

public record RegisterLocalWorkspaceBridgeCommand(
        String tenantId, String userId, String displayName, String deviceId, String rootHandle) { }
