package com.spaceagent.platform.governance.application;

import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalRequest;
import com.spaceagent.platform.governance.domain.ApprovalScope;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernancePolicy;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.governance.domain.GovernanceRepository;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class GovernanceApplicationService implements GovernanceApplicationApi {

    private final GovernanceRepository repository;
    private final IdentityApplicationApi identity;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization;

    public GovernanceApplicationService(
            GovernanceRepository repository,
            IdentityApplicationApi identity,
            IdGenerator ids,
            TimeProvider time,
            com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization) {
        this.repository = repository;
        this.identity = identity;
        this.ids = ids;
        this.time = time;
        this.actorAuthorization = java.util.Objects.requireNonNull(actorAuthorization);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyView getPolicy(ActorQuery query) {
        requireAdministrator(query.tenantId(), query.userId());
        return view(policy(query.tenantId()));
    }

    @Override
    public PolicyView updatePolicy(UpdatePolicyCommand command) {
        requireAdministrator(command.tenantId(), command.userId());
        if (command.expectedRevision() < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        Instant now = time.now();
        GovernancePolicy desired = new GovernancePolicy(
                command.tenantId(),
                command.requireCodingFileApproval(),
                command.requireCommandApproval(),
                command.requireAutomationApproval(),
                command.requireNetworkApproval(),
                command.requireSourceMergeApproval(),
                command.separationOfDuties(),
                command.approvalTtlSeconds(),
                command.expectedRevision() + 1,
                command.userId(),
                now);
        return repository.savePolicy(desired, command.expectedRevision())
                .map(GovernanceApplicationService::view)
                .orElseThrow(() -> conflict(
                        "Governance policy revision conflict",
                        "GOVERNANCE_POLICY_REVISION_CONFLICT"));
    }

    @Override
    public AuthorizationView authorize(AuthorizeCommand command) {
        actorAuthorization.requireActiveActor(command.tenantId(), command.userId(), true);
        requireActiveMember(command.tenantId(), command.userId());
        ApprovalScope scope = new ApprovalScope(
                command.tenantId(), command.userId(), command.actionType(),
                command.resourceType(), command.resourceId(), command.operationHash());
        GovernancePolicy policy = policy(command.tenantId());
        if (!policy.requiresApproval(command.actionType())) {
            return new AuthorizationView(AuthorizationStatus.ALLOWED, null);
        }
        Instant now = time.now();
        if (command.approvalId() == null || command.approvalId().isBlank()) {
            ApprovalRequest request = repository.findPending(scope)
                    .map(value -> expireIfNecessary(value, now))
                    .filter(value -> value.state() == ApprovalState.PENDING)
                    .orElseGet(() -> repository.createOrFindPending(new ApprovalRequest(
                            ids.nextId(), command.tenantId(), command.userId(),
                            command.actionType(), command.resourceType(), command.resourceId(),
                            command.operationHash(), abbreviate(command.summary(), 1_000),
                            ApprovalState.PENDING,
                            now.plusSeconds(policy.approvalTtlSeconds()),
                            null, null, null, null, 1, now, now)));
            return new AuthorizationView(AuthorizationStatus.APPROVAL_REQUIRED, view(request));
        }

        var consumed = repository.consume(command.approvalId(), scope, now);
        if (consumed.isPresent()) {
            return new AuthorizationView(AuthorizationStatus.ALLOWED, view(consumed.get()));
        }
        ApprovalRequest current = repository.findApproval(command.tenantId(), command.approvalId())
                .map(value -> expireIfNecessary(value, now))
                .orElse(null);
        if (current != null && scope.matches(current) && current.state() == ApprovalState.PENDING) {
            return new AuthorizationView(AuthorizationStatus.APPROVAL_REQUIRED, view(current));
        }
        return new AuthorizationView(
                AuthorizationStatus.INVALID_APPROVAL, current == null ? null : view(current));
    }

    @Override
    public ApprovalView requestRequired(RequiredApprovalCommand command) {
        actorAuthorization.requireActiveActor(command.tenantId(), command.userId(), true);
        requireActiveMember(command.tenantId(), command.userId());
        if (command.actionType() != GovernanceActionType.AGENT_CONFIGURATION_CHANGE) {
            throw new IllegalArgumentException("Required approval action is not supported");
        }
        ApprovalScope scope = new ApprovalScope(
                command.tenantId(), command.userId(), command.actionType(),
                command.resourceType(), command.resourceId(), command.operationHash());
        Instant now = time.now();
        ApprovalRequest request = repository.findPending(scope)
                .map(value -> expireIfNecessary(value, now))
                .filter(value -> value.state() == ApprovalState.PENDING)
                .orElseGet(() -> repository.createOrFindPending(new ApprovalRequest(
                        ids.nextId(), command.tenantId(), command.userId(), command.actionType(),
                        command.resourceType(), command.resourceId(), command.operationHash(),
                        abbreviate(command.summary(), 1_000), ApprovalState.PENDING,
                        now.plusSeconds(policy(command.tenantId()).approvalTtlSeconds()),
                        null, null, null, null, 1, now, now)));
        return view(request);
    }

    @Override
    public ApprovalView cancel(CancelApprovalCommand command) {
        requireActiveMember(command.tenantId(), command.userId());
        Instant now = time.now();
        ApprovalRequest request = repository.findApproval(command.tenantId(), command.approvalId())
                .map(value -> expireIfNecessary(value, now))
                .orElseThrow(() -> notFound(command.approvalId()));
        if (!request.requestedBy().equals(command.userId())) {
            throw forbidden("Only the approval requester may replace this request",
                    "GOVERNANCE_APPROVAL_CANCEL_FORBIDDEN");
        }
        if (request.state() != ApprovalState.PENDING) {
            throw conflict("Approval request is not pending: " + request.state(),
                    "GOVERNANCE_APPROVAL_NOT_PENDING");
        }
        return repository.cancel(command.tenantId(), command.approvalId(), request.revision(),
                        command.userId(), now)
                .map(GovernanceApplicationService::view)
                .orElseThrow(() -> conflict("Approval request changed concurrently",
                        "GOVERNANCE_APPROVAL_REVISION_CONFLICT"));
    }

    @Override
    public ApprovalView getApproval(ApprovalQuery query) {
        TenantMembershipView membership = requireActiveMember(query.tenantId(), query.userId());
        ApprovalRequest request = repository.findApproval(query.tenantId(), query.approvalId())
                .map(value -> expireIfNecessary(value, time.now()))
                .orElseThrow(() -> notFound(query.approvalId()));
        if (!request.requestedBy().equals(query.userId()) && !isAdministrator(membership)) {
            throw forbidden("Approval request is not visible to this user");
        }
        return view(request);
    }

    @Override
    public List<ApprovalView> listApprovals(ListApprovalsQuery query) {
        requireAdministrator(query.tenantId(), query.userId());
        int limit = query.limit() <= 0 ? 100 : query.limit();
        if (limit > 200) {
            throw new IllegalArgumentException("limit must not exceed 200");
        }
        return repository.listApprovals(query.tenantId(), query.state(), limit, time.now())
                .stream().map(GovernanceApplicationService::view).toList();
    }

    @Override
    public List<ApprovalView> listRequestedApprovals(RequesterApprovalsQuery query) {
        requireActiveMember(query.tenantId(), query.userId());
        if (query.resourceType() == null || query.resourceType().isBlank()
                || query.resourceType().length() > 80
                || query.resourceId() == null || query.resourceId().isBlank()
                || query.resourceId().length() > 160) {
            throw new IllegalArgumentException("Approval resource scope is invalid");
        }
        int limit = query.limit() <= 0 ? 100 : query.limit();
        if (limit > 200) {
            throw new IllegalArgumentException("limit must not exceed 200");
        }
        return repository.listApprovalsByRequester(
                        query.tenantId(), query.userId(), query.resourceType(), query.resourceId(),
                        query.state(), limit, time.now())
                .stream().map(GovernanceApplicationService::view).toList();
    }

    @Override
    public ApprovalView decide(DecisionCommand command) {
        return decide(command, false);
    }

    @Override
    public ApprovalView decideAgentConfiguration(DecisionCommand command) {
        return decide(command, true);
    }

    private ApprovalView decide(DecisionCommand command, boolean specializedAgentDecision) {
        if (command.decision() != ApprovalState.APPROVED
                && command.decision() != ApprovalState.REJECTED) {
            throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
        }
        Instant now = time.now();
        ApprovalRequest request = repository.findApproval(command.tenantId(), command.approvalId())
                .map(value -> expireIfNecessary(value, now))
                .orElseThrow(() -> notFound(command.approvalId()));
        if (request.actionType() == GovernanceActionType.AGENT_CONFIGURATION_CHANGE) {
            if (!specializedAgentDecision) {
                throw conflict("Agent configuration approval requires its atomic Agent endpoint",
                        "GOVERNANCE_SPECIALIZED_DECISION_REQUIRED");
            }
            requireOwner(command.tenantId(), command.userId());
        } else if (specializedAgentDecision) {
            throw conflict("Approval does not belong to an Agent configuration change",
                    "GOVERNANCE_APPROVAL_SCOPE_MISMATCH");
        } else {
            requireAdministrator(command.tenantId(), command.userId());
        }
        if (request.state() != ApprovalState.PENDING) {
            throw conflict(
                    "Approval request is not pending: " + request.state(),
                    "GOVERNANCE_APPROVAL_NOT_PENDING");
        }
        if (policy(command.tenantId()).separationOfDuties()
                && request.requestedBy().equals(command.userId())) {
            throw forbidden("Separation of duties prevents self-approval",
                    "GOVERNANCE_SELF_APPROVAL_FORBIDDEN");
        }
        return repository.decide(
                        command.tenantId(), command.approvalId(), request.revision(),
                        command.decision(), command.userId(),
                        abbreviateNullable(command.note(), 2_000), now)
                .map(GovernanceApplicationService::view)
                .orElseThrow(() -> conflict(
                        "Approval request changed concurrently",
                        "GOVERNANCE_APPROVAL_REVISION_CONFLICT"));
    }

    private ApprovalRequest expireIfNecessary(ApprovalRequest request, Instant now) {
        if ((request.state() == ApprovalState.PENDING || request.state() == ApprovalState.APPROVED)
                && request.expiredAt(now)) {
            return repository.expire(
                            request.tenantId(), request.id(), request.revision(), now)
                    .orElseGet(() -> repository.findApproval(request.tenantId(), request.id())
                            .orElse(request));
        }
        return request;
    }

    private GovernancePolicy policy(String tenantId) {
        return repository.findPolicy(tenantId).orElseGet(() -> GovernancePolicy.defaults(tenantId));
    }

    private TenantMembershipView requireAdministrator(String tenantId, String userId) {
        TenantMembershipView membership = requireActiveMember(tenantId, userId);
        if (!isAdministrator(membership)) {
            throw forbidden("Organization administrator access is required");
        }
        return membership;
    }

    private TenantMembershipView requireOwner(String tenantId, String userId) {
        TenantMembershipView membership = requireActiveMember(tenantId, userId);
        if (membership.role() != TenantRole.OWNER) {
            throw forbidden("Organization owner access is required",
                    "GOVERNANCE_OWNER_REQUIRED");
        }
        return membership;
    }

    private TenantMembershipView requireActiveMember(String tenantId, String userId) {
        actorAuthorization.requireActiveActor(tenantId, userId, false);
        return identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> forbidden("Active Organization membership is required"));
    }

    private static boolean isAdministrator(TenantMembershipView membership) {
        return membership.role() == TenantRole.OWNER || membership.role() == TenantRole.ADMIN;
    }

    private static PolicyView view(GovernancePolicy policy) {
        return new PolicyView(
                policy.tenantId(), policy.requireCodingFileApproval(),
                policy.requireCommandApproval(), policy.requireAutomationApproval(),
                policy.requireNetworkApproval(),
                policy.requireSourceMergeApproval(), policy.separationOfDuties(),
                policy.approvalTtlSeconds(), policy.revision(),
                policy.updatedBy(), policy.updatedAt());
    }

    private static ApprovalView view(ApprovalRequest request) {
        return new ApprovalView(
                request.id(), request.tenantId(), request.requestedBy(), request.actionType(),
                request.resourceType(), request.resourceId(), request.operationHash(),
                request.summary(), request.state(), request.expiresAt(), request.decidedBy(),
                request.decidedAt(), request.decisionNote(), request.consumedAt(),
                request.revision(), request.createdAt(), request.updatedAt());
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Sensitive operation";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String abbreviateNullable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return abbreviate(value, maxLength);
    }

    private static BusinessException forbidden(String message) {
        return forbidden(message, "GOVERNANCE_ADMIN_REQUIRED");
    }

    private static BusinessException forbidden(String message, String code) {
        return new BusinessException(message, HttpStatus.FORBIDDEN, code);
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static BusinessException notFound(String approvalId) {
        return new BusinessException(
                "Approval request not found: " + approvalId,
                HttpStatus.NOT_FOUND,
                "GOVERNANCE_APPROVAL_NOT_FOUND");
    }
}
