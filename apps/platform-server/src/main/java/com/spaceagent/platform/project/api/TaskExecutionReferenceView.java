package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;

/** Canonical execution reference resolved by the Project module. */
public record TaskExecutionReferenceView(
        String projectId,
        String rootTaskId,
        String taskId,
        String taskPlanId,
        String planStepId,
        TaskPlanStatus taskPlanStatus,
        PlanStepState planStepState,
        TaskState taskState) {
}
