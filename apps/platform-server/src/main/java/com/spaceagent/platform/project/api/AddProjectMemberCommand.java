package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectRole;

public record AddProjectMemberCommand(
        String tenantId,
        String userId,
        String projectId,
        String memberUserId,
        ProjectRole role) {
}
