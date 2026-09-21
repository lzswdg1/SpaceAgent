package com.spaceagent.platform.project.api;

public record ResolveLocalWorkspaceBridgeQuery(
        String tenantId, String userId, String bridgeId, String rootHandle) { }
