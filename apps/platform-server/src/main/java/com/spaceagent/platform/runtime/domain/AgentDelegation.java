package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

public record AgentDelegation(
        String id,
        String tenantId,
        String parentRunId,
        String childRunId,
        String taskPlanId,
        String planStepId,
        String childTaskId,
        String targetAgentId,
        String workspaceId,
        String handoffId,
        AgentDelegationState state,
        Instant createdAt,
        Instant updatedAt) {

    public AgentDelegation {
        require(id, "id");
        require(tenantId, "tenantId");
        require(parentRunId, "parentRunId");
        require(childRunId, "childRunId");
        require(taskPlanId, "taskPlanId");
        require(planStepId, "planStepId");
        require(childTaskId, "childTaskId");
        require(targetAgentId, "targetAgentId");
        require(workspaceId, "workspaceId");
        require(handoffId, "handoffId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public AgentDelegation withState(AgentDelegationState next, Instant at) {
        return new AgentDelegation(
                id, tenantId, parentRunId, childRunId, taskPlanId, planStepId, childTaskId,
                targetAgentId, workspaceId, handoffId, next, createdAt, at);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
