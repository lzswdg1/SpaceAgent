package com.spaceagent.platform.agent.api;

/** Exact Governance capability used by the Agent change-request application service. */
public interface AgentConfigurationApprovalPort {

    ApprovalReference requestRequired(
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String summary);

    ApprovalReference replaceRequired(
            String priorApprovalId,
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String summary);

    ApprovalReference decide(
            String tenantId,
            String organizationOwnerId,
            String approvalId,
            boolean approved,
            String note);

    ApprovalReference consume(
            String tenantId,
            String requestedBy,
            String agentId,
            String operationHash,
            String approvalId);

    ApprovalReference status(String tenantId, String requestedBy, String approvalId);

    record ApprovalReference(String id, String state) {
        public ApprovalReference {
            if (id == null || id.isBlank() || state == null || state.isBlank()) {
                throw new IllegalArgumentException("Approval reference is incomplete");
            }
        }
    }
}
