package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.TaskState;

import java.time.Instant;
import java.util.List;

public record TaskView(
        String id,
        String projectId,
        String parentTaskId,
        String title,
        String goal,
        String description,
        List<String> constraints,
        List<String> acceptanceCriteria,
        String currentTaskPlanId,
        TaskState state,
        Instant createdAt,
        Instant updatedAt,
        String scope,
        String conversationId,
        String sourceMessageId) {

    public TaskView {
        constraints = List.copyOf(constraints);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        scope = scope == null ? (projectId == null ? "CHAT" : "PROJECT") : scope;
    }

    public TaskView(
            String id, String projectId, String parentTaskId, String title, String goal,
            String description, List<String> constraints, List<String> acceptanceCriteria,
            String currentTaskPlanId, TaskState state, Instant createdAt, Instant updatedAt) {
        this(id, projectId, parentTaskId, title, goal, description, constraints,
                acceptanceCriteria, currentTaskPlanId, state, createdAt, updatedAt,
                projectId == null ? "CHAT" : "PROJECT", null, null);
    }
}
