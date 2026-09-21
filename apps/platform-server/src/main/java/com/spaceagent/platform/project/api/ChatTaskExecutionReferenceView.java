package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.TaskState;

public record ChatTaskExecutionReferenceView(
        String taskId,
        String conversationId,
        String sourceMessageId,
        TaskState state) {
}
