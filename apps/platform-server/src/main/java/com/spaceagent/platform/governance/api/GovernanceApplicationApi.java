package com.spaceagent.platform.governance.api;

import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;

import java.time.Instant;
import java.util.List;

public interface GovernanceApplicationApi {

    PolicyView getPolicy(ActorQuery query);

    PolicyView updatePolicy(UpdatePolicyCommand command);

    AuthorizationView authorize(AuthorizeCommand command);

    ApprovalView requestRequired(RequiredApprovalCommand command);

    ApprovalView cancel(CancelApprovalCommand command);

    ApprovalView getApproval(ApprovalQuery query);

    List<ApprovalView> listApprovals(ListApprovalsQuery query);

    List<ApprovalView> listRequestedApprovals(RequesterApprovalsQuery query);

    ApprovalView decide(DecisionCommand command);

    ApprovalView decideAgentConfiguration(DecisionCommand command);

    record ActorQuery(String tenantId, String userId) {}

    record UpdatePolicyCommand(
            String tenantId,
            String userId,
            boolean requireCodingFileApproval,
            boolean requireCommandApproval,
            boolean requireAutomationApproval,
            boolean requireNetworkApproval,
            boolean requireSourceMergeApproval,
            boolean separationOfDuties,
            int approvalTtlSeconds,
            long expectedRevision) {}

    record AuthorizeCommand(
            String tenantId,
            String userId,
            GovernanceActionType actionType,
            String resourceType,
            String resourceId,
            String operationHash,
            String summary,
            String approvalId) {}

    record RequiredApprovalCommand(
            String tenantId,
            String userId,
            GovernanceActionType actionType,
            String resourceType,
            String resourceId,
            String operationHash,
            String summary) {}

    record CancelApprovalCommand(String tenantId, String userId, String approvalId) {}

    record ApprovalQuery(String tenantId, String userId, String approvalId) {}

    record ListApprovalsQuery(
            String tenantId, String userId, ApprovalState state, int limit) {}

    record RequesterApprovalsQuery(
            String tenantId, String userId, String resourceType, String resourceId,
            ApprovalState state, int limit) {}

    record DecisionCommand(
            String tenantId,
            String userId,
            String approvalId,
            ApprovalState decision,
            String note) {}

    record PolicyView(
            String tenantId,
            boolean requireCodingFileApproval,
            boolean requireCommandApproval,
            boolean requireAutomationApproval,
            boolean requireNetworkApproval,
            boolean requireSourceMergeApproval,
            boolean separationOfDuties,
            int approvalTtlSeconds,
            long revision,
            String updatedBy,
            Instant updatedAt) {}

    enum AuthorizationStatus {
        ALLOWED,
        APPROVAL_REQUIRED,
        INVALID_APPROVAL
    }

    record AuthorizationView(
            AuthorizationStatus status, ApprovalView approval) {

        public boolean allowed() {
            return status == AuthorizationStatus.ALLOWED;
        }
    }

    record ApprovalView(
            String id,
            String tenantId,
            String requestedBy,
            GovernanceActionType actionType,
            String resourceType,
            String resourceId,
            String operationHash,
            String summary,
            ApprovalState state,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            String decisionNote,
            Instant consumedAt,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
