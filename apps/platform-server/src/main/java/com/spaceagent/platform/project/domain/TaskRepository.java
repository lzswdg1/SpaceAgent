package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for Project-owned PROJECT and CHAT scoped Tasks. */
public interface TaskRepository {

    void save(Task task);

    Task createOrFindChatRoot(Task task);

    Optional<Task> findById(String taskId);

    Optional<Task> findByIdForUpdate(String taskId);

    Optional<Task> findBySourceMessageId(String sourceMessageId);

    List<Task> findRootsByConversationId(String conversationId, int limit);

    List<Task> findByProjectId(String projectId);
}
