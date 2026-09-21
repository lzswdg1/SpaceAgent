package com.spaceagent.platform.governance;

import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.application.GovernanceApplicationService;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.governance.infrastructure.memory.InMemoryGovernanceRepository;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformGovernanceApplicationTest {

    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    void defaultPolicyIsCompatibleAndRevisionUpdatesAreComparedAndSet() {
        Fixture fixture = new Fixture();
        var defaults = fixture.service.getPolicy(
                new GovernanceApplicationApi.ActorQuery("tenant", "owner"));
        assertThat(defaults.revision()).isZero();
        assertThat(defaults.requireCodingFileApproval()).isFalse();

        var saved = fixture.service.updatePolicy(fixture.policy(false));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.requireCommandApproval()).isTrue();
        assertThatThrownBy(() -> fixture.service.updatePolicy(fixture.policy(false)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode())
                                .isEqualTo("GOVERNANCE_POLICY_REVISION_CONFLICT"));

        assertThatThrownBy(() -> fixture.service.getPolicy(
                new GovernanceApplicationApi.ActorQuery("tenant", "member")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("GOVERNANCE_ADMIN_REQUIRED"));
    }

    @Test
    void pendingRequestIsDeduplicatedAndSeparateAdministratorDecides() {
        Fixture fixture = new Fixture();
        fixture.service.updatePolicy(fixture.policy(true));
        var command = authorize("owner", HASH_A, null);

        var first = fixture.service.authorize(command);
        var duplicate = fixture.service.authorize(command);
        assertThat(first.status()).isEqualTo(
                GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED);
        assertThat(duplicate.approval().id()).isEqualTo(first.approval().id());
        assertThat(fixture.service.listRequestedApprovals(
                new GovernanceApplicationApi.RequesterApprovalsQuery(
                        "tenant", "owner", "WORKSPACE", "workspace", null, 20)))
                .extracting(GovernanceApplicationApi.ApprovalView::id)
                .containsExactly(first.approval().id());

        assertThatThrownBy(() -> fixture.service.decide(
                new GovernanceApplicationApi.DecisionCommand(
                        "tenant", "owner", first.approval().id(),
                        ApprovalState.APPROVED, "self")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode())
                                .isEqualTo("GOVERNANCE_SELF_APPROVAL_FORBIDDEN"));

        var approved = fixture.service.decide(new GovernanceApplicationApi.DecisionCommand(
                "tenant", "admin", first.approval().id(), ApprovalState.APPROVED, "reviewed"));
        assertThat(approved.state()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("admin");
    }

    @Test
    void approvalIsExactExpiringAndConsumedOnlyOnce() {
        Fixture fixture = new Fixture();
        fixture.service.updatePolicy(fixture.policy(false));
        var pending = fixture.service.authorize(authorize("owner", HASH_A, null)).approval();
        fixture.service.decide(new GovernanceApplicationApi.DecisionCommand(
                "tenant", "owner", pending.id(), ApprovalState.APPROVED, null));

        var mismatch = fixture.service.authorize(authorize("owner", HASH_B, pending.id()));
        assertThat(mismatch.status()).isEqualTo(
                GovernanceApplicationApi.AuthorizationStatus.INVALID_APPROVAL);
        var allowed = fixture.service.authorize(authorize("owner", HASH_A, pending.id()));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.approval().state()).isEqualTo(ApprovalState.CONSUMED);
        assertThat(fixture.service.authorize(authorize("owner", HASH_A, pending.id())).status())
                .isEqualTo(GovernanceApplicationApi.AuthorizationStatus.INVALID_APPROVAL);

        var expiring = fixture.service.authorize(authorize("owner", HASH_B, null)).approval();
        fixture.now.set(NOW.plusSeconds(3_601));
        assertThat(fixture.service.getApproval(new GovernanceApplicationApi.ApprovalQuery(
                "tenant", "owner", expiring.id())).state()).isEqualTo(ApprovalState.EXPIRED);
    }

    @Test
    void agentConfigurationApprovalIsAlwaysRequiredAndOwnerOnly() {
        Fixture fixture = new Fixture();
        var required = fixture.service.requestRequired(
                new GovernanceApplicationApi.RequiredApprovalCommand(
                        "tenant", "member", GovernanceActionType.AGENT_CONFIGURATION_CHANGE,
                        "AGENT_CONFIGURATION", "agent", HASH_A, "Update shared Agent"));
        assertThat(required.state()).isEqualTo(ApprovalState.PENDING);
        assertThat(fixture.service.requestRequired(
                new GovernanceApplicationApi.RequiredApprovalCommand(
                        "tenant", "member", GovernanceActionType.AGENT_CONFIGURATION_CHANGE,
                        "AGENT_CONFIGURATION", "agent", HASH_A, "Update shared Agent")).id())
                .isEqualTo(required.id());

        assertThatThrownBy(() -> fixture.service.decide(
                new GovernanceApplicationApi.DecisionCommand(
                        "tenant", "admin", required.id(), ApprovalState.APPROVED, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("GOVERNANCE_SPECIALIZED_DECISION_REQUIRED"));
        assertThatThrownBy(() -> fixture.service.decideAgentConfiguration(
                new GovernanceApplicationApi.DecisionCommand(
                        "tenant", "admin", required.id(), ApprovalState.APPROVED, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("GOVERNANCE_OWNER_REQUIRED"));
        assertThat(fixture.service.decideAgentConfiguration(new GovernanceApplicationApi.DecisionCommand(
                "tenant", "owner", required.id(), ApprovalState.APPROVED, "approved")).state())
                .isEqualTo(ApprovalState.APPROVED);

        var replacement = fixture.service.requestRequired(
                new GovernanceApplicationApi.RequiredApprovalCommand(
                        "tenant", "member", GovernanceActionType.AGENT_CONFIGURATION_CHANGE,
                        "AGENT_CONFIGURATION", "agent", HASH_B, "Replace shared Agent change"));
        assertThat(fixture.service.cancel(new GovernanceApplicationApi.CancelApprovalCommand(
                "tenant", "member", replacement.id())).state())
                .isEqualTo(ApprovalState.CANCELLED);
    }

    private static GovernanceApplicationApi.AuthorizeCommand authorize(
            String userId, String hash, String approvalId) {
        return new GovernanceApplicationApi.AuthorizeCommand(
                "tenant", userId, GovernanceActionType.COMMAND_EXECUTION,
                "WORKSPACE", "workspace", hash, "Run tests", approvalId);
    }

    private static final class Fixture {
        private final AtomicInteger ids = new AtomicInteger();
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        private final GovernanceApplicationService service;

        private Fixture() {
            IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
            membership(identity, "owner", TenantRole.OWNER);
            membership(identity, "admin", TenantRole.ADMIN);
            membership(identity, "member", TenantRole.MEMBER);
            service = new GovernanceApplicationService(
                    new InMemoryGovernanceRepository(), identity,
                    () -> "approval-" + ids.incrementAndGet(), now::get, (t, u, w) -> {});
        }

        private GovernanceApplicationApi.UpdatePolicyCommand policy(boolean separation) {
            return new GovernanceApplicationApi.UpdatePolicyCommand(
                    "tenant", "owner", false, true, false,
                    false, false, separation, 3_600, 0);
        }

        private void membership(
                IdentityApplicationApi identity, String userId, TenantRole role) {
            when(identity.findTenantMembership("tenant", userId))
                    .thenReturn(Optional.of(new TenantMembershipView(
                            "tenant", userId, role, TenantMembershipStatus.ACTIVE, NOW, NOW)));
        }
    }
}
