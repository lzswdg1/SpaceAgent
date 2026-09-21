package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.Task;
import com.spaceagent.platform.project.domain.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Non-authoritative local/test Task adapter. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<String, Task> values = new ConcurrentHashMap<>();

    @Override
    public void save(Task task) {
        values.put(task.id(), task);
    }

    @Override
    public synchronized Task createOrFindChatRoot(Task task) {
        Task existing = values.values().stream()
                .filter(value -> task.sourceMessageId().equals(value.sourceMessageId()))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        values.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return Optional.ofNullable(values.get(taskId));
    }

    @Override
    public Optional<Task> findByIdForUpdate(String taskId) {
        return findById(taskId);
    }

    @Override
    public Optional<Task> findBySourceMessageId(String sourceMessageId) {
        return values.values().stream()
                .filter(task -> sourceMessageId.equals(task.sourceMessageId()))
                .findFirst();
    }

    @Override
    public List<Task> findRootsByConversationId(String conversationId, int limit) {
        return values.values().stream()
                .filter(task -> conversationId.equals(task.conversationId()))
                .filter(task -> task.parentTaskId() == null)
                .sorted(Comparator.comparing(Task::createdAt).reversed()
                        .thenComparing(Task::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<Task> findByProjectId(String projectId) {
        return values.values().stream()
                .filter(task -> projectId.equals(task.projectId()))
                .sorted(Comparator.comparing(Task::createdAt).thenComparing(Task::id))
                .toList();
    }
}
