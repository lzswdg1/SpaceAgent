package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectReconciliationStep;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepRepository;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectReconciliationStepRepository implements ProjectReconciliationStepRepository {
    private final Map<String, ProjectReconciliationStep> values = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ProjectReconciliationStep step) {
        boolean duplicateSourceMerge = values.values().stream().anyMatch(value ->
                value.sourceMergeId().equals(step.sourceMergeId()));
        if (duplicateSourceMerge || values.putIfAbsent(step.id(), step) != null) {
            throw new IllegalStateException("Project reconciliation Step already exists");
        }
    }

    @Override
    public Optional<ProjectReconciliationStep> findById(
            String tenantId, String ownerUserId, String projectId, String reconciliationStepId) {
        return Optional.ofNullable(values.get(reconciliationStepId))
                .filter(value -> scoped(value, tenantId, ownerUserId, projectId));
    }

    @Override
    public Optional<ProjectReconciliationStep> findBySourceMergeId(
            String tenantId, String ownerUserId, String projectId, String sourceMergeId) {
        return values.values().stream()
                .filter(value -> value.sourceMergeId().equals(sourceMergeId))
                .filter(value -> scoped(value, tenantId, ownerUserId, projectId))
                .findFirst();
    }

    @Override
    public synchronized boolean update(
            ProjectReconciliationStep step,
            long expectedRevision,
            ProjectReconciliationStepState expectedState) {
        ProjectReconciliationStep current = values.get(step.id());
        if (current == null || current.revision() != expectedRevision || current.state() != expectedState) {
            return false;
        }
        values.put(step.id(), step);
        return true;
    }

    private static boolean scoped(
            ProjectReconciliationStep value, String tenantId, String ownerUserId, String projectId) {
        return value.tenantId().equals(tenantId)
                && value.ownerUserId().equals(ownerUserId)
                && value.projectId().equals(projectId);
    }
}
