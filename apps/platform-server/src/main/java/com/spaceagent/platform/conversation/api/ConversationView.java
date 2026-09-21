package com.spaceagent.platform.conversation.api;

import com.spaceagent.platform.conversation.domain.ConversationStatus;

import java.time.Instant;

/**
 * Public conversation query result.
 */
public record ConversationView(
        String id,
        String projectId,
        String projectDirectoryId,
        String taskId,
        String activeTaskId,
        String tenantId,
        String userId,
        String agentId,
        String title,
        ConversationStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public ConversationView(
            String id, String projectId, String taskId, String activeTaskId,
            String tenantId, String userId, String agentId, String title,
            ConversationStatus status, Instant createdAt, Instant updatedAt) {
        this(id, projectId, null, taskId, activeTaskId, tenantId, userId, agentId,
                title, status, createdAt, updatedAt);
    }
}
