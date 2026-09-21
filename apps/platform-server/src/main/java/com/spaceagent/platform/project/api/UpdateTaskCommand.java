package com.spaceagent.platform.project.api;

import java.util.List;

public record UpdateTaskCommand(
        String tenantId,
        String userId,
        String projectId,
        String taskId,
        String title,
        String goal,
        String description,
        boolean descriptionPresent,
        List<String> constraints,
        boolean constraintsPresent,
        List<String> acceptanceCriteria,
        boolean acceptanceCriteriaPresent) {
}
