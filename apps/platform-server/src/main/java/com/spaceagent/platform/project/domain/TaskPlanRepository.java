package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for immutable plan structure and lifecycle-only plan updates. */
public interface TaskPlanRepository {

    int nextVersionNumber(String rootTaskId);

    void lockChatProposalSource(String sourceAgentRunId);

    void insert(TaskPlan plan, List<PlanStep> steps, List<PlanStepDependency> dependencies);

    void updateLifecycle(TaskPlan plan);

    void updateStep(PlanStep step);

    Optional<TaskPlan> findById(String planId);

    Optional<TaskPlan> findByIdForUpdate(String planId);

    Optional<TaskPlan> findBySourceAgentRunId(String sourceAgentRunId);

    List<TaskPlan> findByRootTaskId(String rootTaskId);

    List<PlanStep> findSteps(String planId);

    List<PlanStepDependency> findDependencies(String planId);
}
