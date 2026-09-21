package com.spaceagent.platform.project.api;

/** Public Project-owned validation and PlanStep execution transition boundary. */
public interface TaskExecutionApplicationApi {

    TaskExecutionReferenceView resolve(ResolveTaskExecutionReferenceQuery query);

    ChatTaskExecutionReferenceView resolveChatTask(ResolveChatTaskExecutionQuery query);

    TaskExecutionReferenceView transition(TransitionPlanStepExecutionCommand command);

    ChatPlanStepExecutionReferenceView resolveChatPlanStep(
            ResolveChatPlanStepExecutionQuery query);

    ChatPlanStepExecutionReferenceView transitionChatPlanStep(
            TransitionChatPlanStepExecutionCommand command);
}
