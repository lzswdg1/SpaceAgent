package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentConfigurationApprovalPort;
import com.spaceagent.platform.agent.api.AgentConfigurationChangeApplicationApi;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationChangeApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentConfigurationChangeRequestRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.integration.application.OrganizationAgentConfigurationCoordinator;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationAgentConfigurationCoordinatorTest {

    private static final String TENANT = "tenant";
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    private AgentApplicationService agents;
    private AgentConfigurationChangeApplicationService changes;
    private OrganizationAgentConfigurationCoordinator coordinator;
    private FakeApprovals approvals;

    @BeforeEach
    void setUp() {
        AtomicInteger sequence = new AtomicInteger();
        IdGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> NOW.plusSeconds(sequence.get());
        AgentConfigurationFactory factory = new AgentConfigurationFactory(new ObjectMapper());
        agents = new AgentApplicationService(
                new InMemoryAgentRepository(), ids, time, null, null, null,
                factory, null, new InMemoryAgentCurrentConfigurationRepository());
        approvals = new FakeApprovals();
        changes = new AgentConfigurationChangeApplicationService(
                new InMemoryAgentConfigurationChangeRequestRepository(), agents, factory,
                approvals, ids, time);
        IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
        membership(identity, "owner", TenantRole.OWNER);
        membership(identity, "creator", TenantRole.MEMBER);
        membership(identity, "member", TenantRole.MEMBER);
        membership(identity, "admin", TenantRole.ADMIN);
        membership(identity, "viewer", TenantRole.VIEWER);
        coordinator = new OrganizationAgentConfigurationCoordinator(identity, agents, changes);
    }

    @Test
    void creatorAndCurrentOrganizationOwnerSaveDirectly() {
        String agentId = create("creator", "Original");

        var creatorSave = coordinator.save(update("creator", agentId, "Creator save"));
        assertThat(creatorSave.outcome())
                .isEqualTo(OrganizationAgentConfigurationCoordinator.SaveOutcome.APPLIED);
        assertThat(creatorSave.agent().systemPrompt()).isEqualTo("Creator save");

        var ownerSave = coordinator.save(update("owner", agentId, "Owner save"));
        assertThat(ownerSave.outcome())
                .isEqualTo(OrganizationAgentConfigurationCoordinator.SaveOutcome.APPLIED);
        assertThat(ownerSave.agent().systemPrompt()).isEqualTo("Owner save");
        assertThat(ownerSave.agent().ownerId()).isEqualTo("creator");
        assertThat(approvals.created).isZero();
    }

    @Test
    void nonOwnerSaveWaitsForOwnerAndApprovalAppliesOnce() {
        String agentId = create("creator", "Original");

        var pending = coordinator.save(update("member", agentId, "Proposed"));
        assertThat(pending.outcome())
                .isEqualTo(OrganizationAgentConfigurationCoordinator.SaveOutcome.PENDING_APPROVAL);
        assertThat(pending.changeRequest().state())
                .isEqualTo(com.spaceagent.platform.agent.domain.AgentConfigurationChangeState.PENDING);
        assertThat(agents.findById(agentId).orElseThrow().systemPrompt()).isEqualTo("Original");

        var applied = coordinator.decide(
                TENANT, "owner", agentId, pending.changeRequest().id(), pending.changeRequest().revision(),
                AgentConfigurationChangeApplicationApi.Decision.APPROVE, "approved");
        assertThat(applied.state())
                .isEqualTo(com.spaceagent.platform.agent.domain.AgentConfigurationChangeState.APPLIED);
        assertThat(applied.proposal()).isNull();
        assertThat(agents.findById(agentId).orElseThrow().systemPrompt()).isEqualTo("Proposed");
        assertThat(approvals.consumed).isEqualTo(1);

        assertThat(coordinator.decide(
                TENANT, "owner", agentId, applied.id(), pending.changeRequest().revision(),
                AgentConfigurationChangeApplicationApi.Decision.APPROVE, "lost response replay"))
                .isEqualTo(applied);
        assertThat(approvals.consumed).isEqualTo(1);
    }

    @Test
    void staleProposalCannotOverwriteNewerDirectSaveAndOnlyOwnerCanDecide() {
        String agentId = create("creator", "Original");
        var pending = coordinator.save(update("admin", agentId, "Old proposal"));

        assertThatThrownBy(() -> coordinator.decide(
                TENANT, "creator", agentId, pending.changeRequest().id(), pending.changeRequest().revision(),
                AgentConfigurationChangeApplicationApi.Decision.APPROVE, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("AGENT_CONFIGURATION_OWNER_APPROVAL_REQUIRED"));

        coordinator.save(update("creator", agentId, "New direct state"));
        var stale = coordinator.decide(
                TENANT, "owner", agentId, pending.changeRequest().id(), pending.changeRequest().revision(),
                AgentConfigurationChangeApplicationApi.Decision.APPROVE, null);
        assertThat(stale.state())
                .isEqualTo(com.spaceagent.platform.agent.domain.AgentConfigurationChangeState.STALE);
        assertThat(stale.proposal()).isNull();
        assertThat(agents.findById(agentId).orElseThrow().systemPrompt())
                .isEqualTo("New direct state");

        assertThatThrownBy(() -> coordinator.save(update("viewer", agentId, "Forbidden")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("AGENT_CONFIGURATION_WRITE_FORBIDDEN"));
    }

    private String create(String ownerId, String prompt) {
        return agents.create(new CreateAgentDefinitionCommand(
                ownerId, TENANT, "Agent " + ownerId, null, prompt,
                null, null, null, 0.2, 4096, 25, "ask",
                true, false, false, List.of(), List.of(), List.of())).id();
    }

    private static UpdateAgentDefinitionCommand update(
            String actorId, String agentId, String prompt) {
        return new UpdateAgentDefinitionCommand(
                actorId, TENANT, agentId, null, null, prompt,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static void membership(
            IdentityApplicationApi identity, String userId, TenantRole role) {
        when(identity.findTenantMembership(TENANT, userId)).thenReturn(Optional.of(
                new TenantMembershipView(
                        TENANT, userId, role, TenantMembershipStatus.ACTIVE, NOW, NOW)));
    }

    private static final class FakeApprovals implements AgentConfigurationApprovalPort {
        private final AtomicInteger sequence = new AtomicInteger();
        private final java.util.Map<String, String> states = new java.util.HashMap<>();
        private int created;
        private int consumed;

        @Override
        public ApprovalReference requestRequired(
                String tenantId, String requestedBy, String agentId,
                String operationHash, String summary) {
            String id = "approval-" + sequence.incrementAndGet();
            states.put(id, "PENDING");
            created++;
            return new ApprovalReference(id, "PENDING");
        }

        @Override
        public ApprovalReference replaceRequired(
                String priorApprovalId, String tenantId, String requestedBy, String agentId,
                String operationHash, String summary) {
            states.put(priorApprovalId, "CANCELLED");
            return requestRequired(tenantId, requestedBy, agentId, operationHash, summary);
        }

        @Override
        public ApprovalReference decide(
                String tenantId, String organizationOwnerId, String approvalId,
                boolean approved, String note) {
            states.put(approvalId, approved ? "APPROVED" : "REJECTED");
            return new ApprovalReference(approvalId, states.get(approvalId));
        }

        @Override
        public ApprovalReference consume(
                String tenantId, String requestedBy, String agentId,
                String operationHash, String approvalId) {
            assertThat(states.get(approvalId)).isEqualTo("APPROVED");
            states.put(approvalId, "CONSUMED");
            consumed++;
            return new ApprovalReference(approvalId, "CONSUMED");
        }

        @Override
        public ApprovalReference status(String tenantId, String requestedBy, String approvalId) {
            return new ApprovalReference(approvalId, states.get(approvalId));
        }
    }
}
