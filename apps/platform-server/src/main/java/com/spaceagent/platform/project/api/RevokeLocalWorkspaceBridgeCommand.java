package com.spaceagent.platform.project.api;

public record RevokeLocalWorkspaceBridgeCommand(
        String tenantId, String userId, String bridgeId) { }
