package com.spaceagent.platform.project.api;

import java.util.List;

/** Validated LangGraph proposal input; Java creates every durable identifier. */
public record CreateChatTaskPlanProposalCommand(
        String tenantId,
        String userId,
        String conversationId,
        String rootTaskId,
        String sourceAgentRunId,
        String generatedByConfigurationHash,
        String strategySummary,
        List<StepProposal> steps,
        String generatedByAgentId) {

    public CreateChatTaskPlanProposalCommand(
            String tenantId, String userId, String conversationId, String rootTaskId,
            String sourceAgentRunId, String generatedByConfigurationHash,
            String strategySummary, List<StepProposal> steps) {
        this(tenantId, userId, conversationId, rootTaskId, sourceAgentRunId,
                generatedByConfigurationHash, strategySummary, steps, null);
    }

    public CreateChatTaskPlanProposalCommand {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record StepProposal(
            String stepKey,
            String goal,
            List<String> dependsOnStepKeys) {

        public StepProposal {
            dependsOnStepKeys = dependsOnStepKeys == null
                    ? List.of() : List.copyOf(dependsOnStepKeys);
        }
    }
}
