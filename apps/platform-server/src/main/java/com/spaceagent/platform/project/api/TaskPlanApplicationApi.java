package com.spaceagent.platform.project.api;

import java.util.List;

public interface TaskPlanApplicationApi {

    TaskPlanView createPlan(CreateTaskPlanCommand command);

    TaskPlanView getPlan(GetTaskPlanQuery query);

    List<TaskPlanView> listPlans(ListTaskPlansQuery query);

    TaskPlanView transition(TaskPlanActionCommand command);

    TaskPlanView createChatProposal(CreateChatTaskPlanProposalCommand command);

    TaskPlanView getChatPlan(GetChatTaskPlanQuery query);

    TaskPlanView getChatPlanBySourceRun(GetChatTaskPlanBySourceRunQuery query);

    List<TaskPlanView> listChatPlans(ListChatTaskPlansQuery query);

    TaskPlanView transitionChatPlan(ChatTaskPlanActionCommand command);

    ChatPlanStepExecutionReferenceView transitionChatPlanStep(
            TransitionChatPlanStepExecutionCommand command);
}
