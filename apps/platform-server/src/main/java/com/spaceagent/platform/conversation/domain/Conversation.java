package com.spaceagent.platform.conversation.domain;

import java.time.Instant;

/**
 * A durable conversation. Conversation owns message and snapshot state; it does
 * not orchestrate agent runs.
 */
public record Conversation(
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

    public Conversation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        title = title == null || title.isBlank() ? "New conversation" : title;
        status = status == null ? ConversationStatus.ACTIVE : status;
    }

    /**
     * Compatibility constructor before ProjectDirectory hierarchy.
     */
    public Conversation(
            String id,
            String projectId,
            String taskId,
            String activeTaskId,
            String tenantId,
            String userId,
            String agentId,
            String title,
            ConversationStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, null, taskId, activeTaskId, tenantId, userId, agentId,
                title, status, createdAt, updatedAt);
    }

    /**
     * Compatibility constructor before explicit active Task support.
     */
    public Conversation(
            String id,
            String projectId,
            String taskId,
            String tenantId,
            String userId,
            String agentId,
            String title,
            ConversationStatus status,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, null, taskId, null, tenantId, userId, agentId,
                title, status, createdAt, updatedAt);
    }

    /**
     * Backward-compatible constructor used by earlier platform scaffolding.
     */
    public Conversation(
            String id,
            String projectId,
            String taskId,
            String title,
            Instant createdAt,
            Instant updatedAt) {
        this(id, projectId, null, taskId, null, null, null, null,
                title, ConversationStatus.ACTIVE, createdAt, updatedAt);
    }

    public Conversation touch(Instant at) {
        return new Conversation(
                id, projectId, projectDirectoryId, taskId, activeTaskId,
                tenantId, userId, agentId,
                title, status, createdAt, at);
    }

    public Conversation rename(String nextTitle, Instant at) {
        if (nextTitle == null || nextTitle.isBlank() || nextTitle.length() > 200) {
            throw new IllegalArgumentException("Conversation title must contain 1 to 200 characters");
        }
        if (status != ConversationStatus.ACTIVE) throw new IllegalStateException("Conversation is closed");
        return new Conversation(id, projectId, projectDirectoryId, taskId, activeTaskId,
                tenantId, userId, agentId, nextTitle.trim(), status, createdAt, at);
    }

    public Conversation bindAgent(String nextAgentId) {
        return new Conversation(
                id, projectId, projectDirectoryId, taskId, activeTaskId,
                tenantId, userId, nextAgentId,
                title, status, createdAt, updatedAt);
    }

    public Conversation switchAgent(String nextAgentId, Instant at) {
        if (status != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("Only active Conversations can switch Agent");
        }
        return new Conversation(
                id, projectId, projectDirectoryId, taskId, activeTaskId,
                tenantId, userId, nextAgentId,
                title, status, createdAt, at);
    }

    public Conversation focusTask(String nextActiveTaskId, Instant at) {
        return new Conversation(
                id, projectId, projectDirectoryId, taskId, nextActiveTaskId,
                tenantId, userId, agentId,
                title, status, createdAt, at);
    }
}
