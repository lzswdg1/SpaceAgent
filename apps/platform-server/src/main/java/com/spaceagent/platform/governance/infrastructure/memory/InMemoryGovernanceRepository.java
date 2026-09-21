package com.spaceagent.platform.governance.infrastructure.memory;

import com.spaceagent.platform.governance.domain.ApprovalRequest;
import com.spaceagent.platform.governance.domain.ApprovalScope;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernancePolicy;
import com.spaceagent.platform.governance.domain.GovernanceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryGovernanceRepository implements GovernanceRepository {

    private final Map<String, GovernancePolicy> policies = new HashMap<>();
    private final Map<String, ApprovalRequest> approvals = new HashMap<>();

    @Override
    public synchronized Optional<GovernancePolicy> findPolicy(String tenantId) {
        return Optional.ofNullable(policies.get(tenantId));
    }

    @Override
    public synchronized Optional<GovernancePolicy> savePolicy(
            GovernancePolicy policy, long expectedRevision) {
        GovernancePolicy current = policies.get(policy.tenantId());
        long currentRevision = current == null ? 0 : current.revision();
        if (currentRevision != expectedRevision || policy.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        policies.put(policy.tenantId(), policy);
        return Optional.of(policy);
    }

    @Override
    public synchronized Optional<ApprovalRequest> findApproval(
            String tenantId, String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId))
                .filter(value -> value.tenantId().equals(tenantId));
    }

    @Override
    public synchronized Optional<ApprovalRequest> findPending(ApprovalScope scope) {
        return approvals.values().stream()
                .filter(value -> value.state() == ApprovalState.PENDING)
                .filter(scope::matches)
                .findFirst();
    }

    @Override
    public synchronized ApprovalRequest createOrFindPending(ApprovalRequest request) {
        return findPending(request.scope()).orElseGet(() -> {
            approvals.put(request.id(), request);
            return request;
        });
    }

    @Override
    public synchronized List<ApprovalRequest> listApprovals(
            String tenantId, ApprovalState state, int limit, Instant now) {
        expireTenant(tenantId, now);
        return approvals.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> state == null || value.state() == state)
                .sorted(Comparator.comparing(ApprovalRequest::createdAt).reversed()
                        .thenComparing(ApprovalRequest::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<ApprovalRequest> listApprovalsByRequester(
            String tenantId, String requestedBy, String resourceType, String resourceId,
            ApprovalState state, int limit, Instant now) {
        expireTenant(tenantId, now);
        return approvals.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.requestedBy().equals(requestedBy))
                .filter(value -> value.resourceType().equals(resourceType))
                .filter(value -> value.resourceId().equals(resourceId))
                .filter(value -> state == null || value.state() == state)
                .sorted(Comparator.comparing(ApprovalRequest::createdAt).reversed()
                        .thenComparing(ApprovalRequest::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<ApprovalRequest> decide(
            String tenantId,
            String approvalId,
            long expectedRevision,
            ApprovalState decision,
            String decidedBy,
            String note,
            Instant now) {
        ApprovalRequest current = approvals.get(approvalId);
        if (current == null || !current.tenantId().equals(tenantId)
                || current.state() != ApprovalState.PENDING
                || current.revision() != expectedRevision || current.expiredAt(now)) {
            return Optional.empty();
        }
        ApprovalRequest updated = copy(
                current, decision, decidedBy, now, note, null, now);
        approvals.put(updated.id(), updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<ApprovalRequest> consume(
            String approvalId, ApprovalScope scope, Instant now) {
        ApprovalRequest current = approvals.get(approvalId);
        if (current == null || current.state() != ApprovalState.APPROVED
                || current.expiredAt(now) || !scope.matches(current)) {
            return Optional.empty();
        }
        ApprovalRequest updated = copy(
                current, ApprovalState.CONSUMED, current.decidedBy(), current.decidedAt(),
                current.decisionNote(), now, now);
        approvals.put(updated.id(), updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<ApprovalRequest> expire(
            String tenantId, String approvalId, long expectedRevision, Instant now) {
        ApprovalRequest current = approvals.get(approvalId);
        if (current == null || !current.tenantId().equals(tenantId)
                || current.revision() != expectedRevision
                || (current.state() != ApprovalState.PENDING
                    && current.state() != ApprovalState.APPROVED)
                || !current.expiredAt(now)) {
            return Optional.empty();
        }
        ApprovalRequest updated = copy(
                current, ApprovalState.EXPIRED, current.decidedBy(), current.decidedAt(),
                current.decisionNote(), current.consumedAt(), now);
        approvals.put(updated.id(), updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<ApprovalRequest> cancel(
            String tenantId,
            String approvalId,
            long expectedRevision,
            String requestedBy,
            Instant now) {
        ApprovalRequest current = approvals.get(approvalId);
        if (current == null || !current.tenantId().equals(tenantId)
                || !current.requestedBy().equals(requestedBy)
                || current.revision() != expectedRevision
                || current.state() != ApprovalState.PENDING) {
            return Optional.empty();
        }
        ApprovalRequest updated = copy(
                current, ApprovalState.CANCELLED, null, null, null, null, now);
        approvals.put(updated.id(), updated);
        return Optional.of(updated);
    }

    private void expireTenant(String tenantId, Instant now) {
        approvals.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() == ApprovalState.PENDING
                        || value.state() == ApprovalState.APPROVED)
                .filter(value -> value.expiredAt(now))
                .toList()
                .forEach(value -> approvals.put(value.id(), copy(
                        value, ApprovalState.EXPIRED, value.decidedBy(), value.decidedAt(),
                        value.decisionNote(), value.consumedAt(), now)));
    }

    private static ApprovalRequest copy(
            ApprovalRequest value,
            ApprovalState state,
            String decidedBy,
            Instant decidedAt,
            String note,
            Instant consumedAt,
            Instant updatedAt) {
        return new ApprovalRequest(
                value.id(), value.tenantId(), value.requestedBy(), value.actionType(),
                value.resourceType(), value.resourceId(), value.operationHash(), value.summary(),
                state, value.expiresAt(), decidedBy, decidedAt, note, consumedAt,
                value.revision() + 1, value.createdAt(), updatedAt);
    }
}
