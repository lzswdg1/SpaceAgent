package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.PlanStep;
import com.spaceagent.platform.project.domain.PlanStepDependency;
import com.spaceagent.platform.project.domain.TaskPlan;
import com.spaceagent.platform.project.domain.TaskPlanRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryTaskPlanRepository implements TaskPlanRepository {

    private final Map<String, TaskPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, List<PlanStep>> steps = new ConcurrentHashMap<>();
    private final Map<String, List<PlanStepDependency>> dependencies = new ConcurrentHashMap<>();

    @Override
    public synchronized int nextVersionNumber(String rootTaskId) {
        return plans.values().stream()
                .filter(plan -> rootTaskId.equals(plan.rootTaskId()))
                .mapToInt(TaskPlan::versionNumber)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public synchronized void lockChatProposalSource(String sourceAgentRunId) {
        // synchronized repository methods provide the in-memory test equivalent.
    }

    @Override
    public synchronized void insert(
            TaskPlan plan,
            List<PlanStep> planSteps,
            List<PlanStepDependency> planDependencies) {
        boolean duplicateVersion = plans.values().stream()
                .anyMatch(existing -> existing.rootTaskId().equals(plan.rootTaskId())
                        && existing.versionNumber() == plan.versionNumber());
        if (duplicateVersion || plans.putIfAbsent(plan.id(), plan) != null) {
            throw new IllegalStateException("TaskPlan already exists");
        }
        steps.put(plan.id(), List.copyOf(planSteps));
        dependencies.put(plan.id(), List.copyOf(planDependencies));
    }

    @Override
    public synchronized void updateLifecycle(TaskPlan plan) {
        TaskPlan current = plans.get(plan.id());
        if (current == null) {
            throw new IllegalStateException("TaskPlan not found");
        }
        if (!java.util.Objects.equals(current.projectId(), plan.projectId())
                || !java.util.Objects.equals(current.conversationId(), plan.conversationId())
                || !current.rootTaskId().equals(plan.rootTaskId())
                || current.versionNumber() != plan.versionNumber()) {
            throw new IllegalStateException("TaskPlan structure is immutable");
        }
        plans.put(plan.id(), plan);
    }

    @Override
    public synchronized void updateStep(PlanStep step) {
        List<PlanStep> current = steps.get(step.taskPlanId());
        if (current == null || current.stream().noneMatch(value -> value.id().equals(step.id()))) {
            throw new IllegalStateException("PlanStep not found");
        }
        steps.put(step.taskPlanId(), current.stream()
                .map(value -> value.id().equals(step.id()) ? step : value)
                .toList());
    }

    @Override
    public Optional<TaskPlan> findById(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Optional<TaskPlan> findByIdForUpdate(String planId) {
        return findById(planId);
    }

    @Override
    public Optional<TaskPlan> findBySourceAgentRunId(String sourceAgentRunId) {
        return plans.values().stream()
                .filter(plan -> sourceAgentRunId.equals(plan.sourceAgentRunId()))
                .findFirst();
    }

    @Override
    public List<TaskPlan> findByRootTaskId(String rootTaskId) {
        return plans.values().stream()
                .filter(plan -> rootTaskId.equals(plan.rootTaskId()))
                .sorted(Comparator.comparingInt(TaskPlan::versionNumber))
                .toList();
    }

    @Override
    public List<PlanStep> findSteps(String planId) {
        return steps.getOrDefault(planId, List.of()).stream()
                .sorted(Comparator.comparingInt(PlanStep::sequence))
                .toList();
    }

    @Override
    public List<PlanStepDependency> findDependencies(String planId) {
        return dependencies.getOrDefault(planId, List.of());
    }
}
