package com.spaceagent.platform.governance.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic persistence port for policy revisions and approval state transitions. */
public interface GovernanceRepository {

    Optional<GovernancePolicy> findPolicy(String tenantId);

    Optional<GovernancePolicy> savePolicy(GovernancePolicy policy, long expectedRevision);

    Optional<ApprovalRequest> findApproval(String tenantId, String approvalId);

    Optional<ApprovalRequest> findPending(ApprovalScope scope);

    ApprovalRequest createOrFindPending(ApprovalRequest request);

    List<ApprovalRequest> listApprovals(
            String tenantId, ApprovalState state, int limit, Instant now);

    List<ApprovalRequest> listApprovalsByRequester(
            String tenantId, String requestedBy, String resourceType, String resourceId,
            ApprovalState state, int limit, Instant now);

    Optional<ApprovalRequest> decide(
            String tenantId,
            String approvalId,
            long expectedRevision,
            ApprovalState decision,
            String decidedBy,
            String note,
            Instant now);

    Optional<ApprovalRequest> consume(
            String approvalId, ApprovalScope scope, Instant now);

    Optional<ApprovalRequest> expire(
            String tenantId, String approvalId, long expectedRevision, Instant now);

    Optional<ApprovalRequest> cancel(
            String tenantId, String approvalId, long expectedRevision, String requestedBy, Instant now);
}
