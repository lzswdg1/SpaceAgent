package com.spaceagent.platform.project.api;

import java.util.List;

public record CreateTaskCommand(
        String tenantId,
        String userId,
        String projectId,
        String parentTaskId,
        String title,
        String goal,
        String description,
        List<String> constraints,
        List<String> acceptanceCriteria) {
}
