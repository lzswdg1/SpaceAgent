package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;

public record ChatPlanStepExecutionReferenceView(
        String conversationId, String rootTaskId, String taskId,
        String taskPlanId, String planStepId, TaskPlanStatus taskPlanStatus,
        PlanStepState planStepState, TaskState taskState) {
}
