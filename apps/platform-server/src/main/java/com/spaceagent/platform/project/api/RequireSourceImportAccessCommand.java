package com.spaceagent.platform.project.api;

public record RequireSourceImportAccessCommand(
        String tenantId, String userId, String projectId) {
}
