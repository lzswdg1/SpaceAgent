package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.agent.api.AgentConfigurationApprovalPort;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import org.springframework.stereotype.Component;

@Component
public class GovernanceAgentConfigurationApprovalAdapter
        implements AgentConfigurationApprovalPort {

    private static final String RESOURCE_TYPE = "AGENT_CONFIGURATION";

    private final GovernanceApplicationApi governance;

    public GovernanceAgentConfigurationApprovalAdapter(GovernanceApplicationApi governance) {
        this.governance = governance;
    }

    @Override
    public ApprovalReference requestRequired(
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String summary) {
        return reference(governance.requestRequired(
                new GovernanceApplicationApi.RequiredApprovalCommand(
                        tenantId, requestedBy, GovernanceActionType.AGENT_CONFIGURATION_CHANGE,
                        RESOURCE_TYPE, agentId, operationHash, summary)));
    }

    @Override
    public ApprovalReference replaceRequired(
            String priorApprovalId,
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String summary) {
        governance.cancel(new GovernanceApplicationApi.CancelApprovalCommand(
                tenantId, requestedBy, priorApprovalId));
        return requestRequired(tenantId, requestedBy, agentId, operationHash, summary);
    }

    @Override
    public ApprovalReference decide(
            String tenantId,
            String organizationOwnerId,
            String approvalId,
            boolean approved,
            String note) {
        return reference(governance.decideAgentConfiguration(new GovernanceApplicationApi.DecisionCommand(
                tenantId, organizationOwnerId, approvalId,
                approved ? ApprovalState.APPROVED : ApprovalState.REJECTED, note)));
    }

    @Override
    public ApprovalReference consume(
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String approvalId) {
        GovernanceApplicationApi.AuthorizationView result = governance.authorize(
                new GovernanceApplicationApi.AuthorizeCommand(
                        tenantId, requestedBy, GovernanceActionType.AGENT_CONFIGURATION_CHANGE,
                        RESOURCE_TYPE, agentId, operationHash, "Apply approved Agent configuration",
                        approvalId));
        if (!result.allowed() || result.approval() == null) {
            throw new IllegalStateException("Approved Agent configuration capability could not be consumed");
        }
        return reference(result.approval());
    }

    @Override
    public ApprovalReference status(String tenantId, String requestedBy, String approvalId) {
        return reference(governance.getApproval(
                new GovernanceApplicationApi.ApprovalQuery(tenantId, requestedBy, approvalId)));
    }

    private static ApprovalReference reference(GovernanceApplicationApi.ApprovalView value) {
        return new ApprovalReference(value.id(), value.state().name());
    }
}
