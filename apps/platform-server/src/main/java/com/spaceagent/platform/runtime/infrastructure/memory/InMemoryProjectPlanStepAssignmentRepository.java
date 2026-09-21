package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectPlanStepAssignmentRepository implements ProjectPlanStepAssignmentRepository {
    private final Map<String, ProjectPlanStepAssignment> values = new LinkedHashMap<>();

    @Override
    public synchronized void insert(ProjectPlanStepAssignment assignment) {
        if (values.containsKey(assignment.id()) || values.values().stream().anyMatch(value ->
                value.taskPlanId().equals(assignment.taskPlanId())
                        && value.planStepId().equals(assignment.planStepId())
                        && value.revision() == assignment.revision())) {
            throw new IllegalStateException("Project PlanStep assignment already exists");
        }
        values.put(assignment.id(), assignment);
    }

    @Override
    public synchronized Optional<ProjectPlanStepAssignment> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public synchronized Optional<ProjectPlanStepAssignment> findLatest(
            String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId) {
        return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId))
                .filter(value -> value.projectId().equals(projectId) && value.taskPlanId().equals(taskPlanId))
                .filter(value -> value.planStepId().equals(planStepId))
                .max(java.util.Comparator.comparingLong(ProjectPlanStepAssignment::revision));
    }

    @Override
    public synchronized Optional<ProjectPlanStepAssignment> findLatestForUpdate(
            String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId) {
        return findLatest(tenantId, ownerId, projectId, taskPlanId, planStepId);
    }
}
