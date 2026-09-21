package com.spaceagent.platform.project.api;

public record RemoveProjectMemberCommand(
        String tenantId,
        String userId,
        String projectId,
        String memberUserId) {
}
