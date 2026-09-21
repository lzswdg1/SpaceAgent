package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectPlanExecution;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryProjectPlanExecutionRepository implements ProjectPlanExecutionRepository {

    private final Map<String, ProjectPlanExecution> values = new LinkedHashMap<>();

    @Override
    public synchronized void insert(ProjectPlanExecution execution) {
        if (!insertIfAbsent(execution)) {
            throw new IllegalStateException("Project plan execution exists");
        }
    }

    @Override
    public synchronized boolean insertIfAbsent(ProjectPlanExecution execution) {
        if (values.containsKey(execution.id())
                || values.values().stream().anyMatch(candidate ->
                candidate.taskPlanId().equals(execution.taskPlanId()))) {
            return false;
        }
        values.put(execution.id(), execution);
        return true;
    }

    @Override
    public synchronized Optional<ProjectPlanExecution> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public synchronized Optional<ProjectPlanExecution> findByIdForUpdate(String id) {
        return findById(id);
    }

    @Override
    public synchronized Optional<ProjectPlanExecution> findByTaskPlanId(
            String tenantId, String ownerId, String taskPlanId) {
        return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.taskPlanId().equals(taskPlanId))
                .findFirst();
    }

    @Override
    public synchronized Optional<ProjectPlanExecution> findByTaskPlanIdForUpdate(
            String tenantId, String ownerId, String taskPlanId) {
        return findByTaskPlanId(tenantId, ownerId, taskPlanId);
    }

    @Override
    public synchronized java.util.List<ProjectPlanExecution> findByProject(
            String projectId, String ownerId, int offset, int limit) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId))
                .filter(value -> value.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(ProjectPlanExecution::createdAt).reversed()
                        .thenComparing(ProjectPlanExecution::id, Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countByProject(String projectId, String ownerId) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId))
                .filter(value -> value.ownerId().equals(ownerId))
                .count();
    }

    @Override
    public synchronized java.util.List<ProjectPlanExecution> findControlTransitions(int limit) {
        return values.values().stream()
                .filter(value -> value.state() == ProjectPlanExecutionState.PAUSING
                        || value.state() == ProjectPlanExecutionState.CANCELLING)
                .sorted(Comparator.comparing(ProjectPlanExecution::updatedAt)
                        .thenComparing(ProjectPlanExecution::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized void saveLifecycle(ProjectPlanExecution expected, ProjectPlanExecution updated) {
        var current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()) {
            throw new IllegalStateException("Project plan execution revision conflict");
        }
        values.put(updated.id(), updated);
    }

    @Override
    public synchronized boolean saveStateIfMatch(
            String id, String tenantId, String ownerId, long revision, ProjectPlanExecution updated) {
        var current = values.get(id);
        if (current == null
                || !current.tenantId().equals(tenantId)
                || !current.ownerId().equals(ownerId)
                || current.revision() != revision) {
            return false;
        }
        values.put(updated.id(), updated);
        return true;
    }

    @Override
    public synchronized int cleanupOldCompleted(Instant before, int limit) {
        if (limit <= 0 || before == null) {
            return 0;
        }
        var obsolete = values.values().stream()
                .filter(value -> isTerminal(value.state()))
                .filter(value -> value.completedAt() != null && value.completedAt().isBefore(before))
                .sorted(Comparator.comparing(ProjectPlanExecution::completedAt)
                        .thenComparing(ProjectPlanExecution::createdAt)
                        .thenComparing(ProjectPlanExecution::id))
                .limit(limit)
                .map(ProjectPlanExecution::id)
                .toList();
        obsolete.forEach(values::remove);
        return obsolete.size();
    }

    private static boolean isTerminal(ProjectPlanExecutionState state) {
        return state == ProjectPlanExecutionState.COMPLETED
                || state == ProjectPlanExecutionState.FAILED
                || state == ProjectPlanExecutionState.BLOCKED
                || state == ProjectPlanExecutionState.CANCELLED;
    }
}
