package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentConfigurationChangeApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Coordinates current Identity role truth with Agent and Governance public application APIs. */
@Service
@Transactional
public class OrganizationAgentConfigurationCoordinator {

    private final IdentityApplicationApi identity;
    private final AgentApplicationApi agents;
    private final AgentConfigurationChangeApplicationApi changes;

    public OrganizationAgentConfigurationCoordinator(
            IdentityApplicationApi identity,
            AgentApplicationApi agents,
            AgentConfigurationChangeApplicationApi changes) {
        this.identity = identity;
        this.agents = agents;
        this.changes = changes;
    }

    public SaveResult save(UpdateAgentDefinitionCommand command) {
        TenantMembershipView membership = activeMembership(command.tenantId(), command.ownerId());
        AgentDefinitionView target = requireTenantAgent(command.tenantId(), command.agentId());
        if (membership.role() == TenantRole.VIEWER) {
            throw forbidden("Organization write access is required",
                    "AGENT_CONFIGURATION_WRITE_FORBIDDEN");
        }
        if (target.ownerId().equals(command.ownerId())) {
            return new SaveResult(SaveOutcome.APPLIED, agents.update(command), null);
        }
        if (membership.role() == TenantRole.OWNER) {
            return new SaveResult(
                    SaveOutcome.APPLIED, agents.updateAsOrganizationOwner(command), null);
        }
        return new SaveResult(SaveOutcome.PENDING_APPROVAL, target, changes.request(command));
    }

    @Transactional(readOnly = true)
    public List<OrganizationAgentAccessView> listOrganizationAgents(
            String tenantId, String actorId) {
        TenantMembershipView membership = activeMembership(tenantId, actorId);
        return agents.listByTenant(tenantId).stream()
                .map(agent -> access(agent, actorId, membership.role()))
                .toList();
    }

    public List<OrganizationAgentAccessView> pageOrganizationAgents(String tenantId, String actorId, int offset, int limit) {
        var membership = activeMembership(tenantId, actorId);
        return agents.page(tenantId, null, offset, limit).stream().map(agent -> access(agent, actorId, membership.role())).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationAgentAccessView getOrganizationAgent(
            String tenantId, String actorId, String agentId) {
        TenantMembershipView membership = activeMembership(tenantId, actorId);
        return access(requireTenantAgent(tenantId, agentId), actorId, membership.role());
    }

    @Transactional(readOnly = true)
    public List<AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView> listChanges(
            String tenantId, String actorId, String agentId, int offset, int limit) {
        TenantMembershipView membership = activeMembership(tenantId, actorId);
        requireTenantAgent(tenantId, agentId);
        return changes.list(new AgentConfigurationChangeApplicationApi.ListQuery(
                tenantId, actorId, membership.role() == TenantRole.OWNER,
                agentId, offset, limit));
    }

    @Transactional(readOnly = true)
    public AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView getChange(
            String tenantId, String actorId, String agentId, String requestId) {
        TenantMembershipView membership = activeMembership(tenantId, actorId);
        requireTenantAgent(tenantId, agentId);
        var change = changes.get(new AgentConfigurationChangeApplicationApi.GetQuery(
                tenantId, actorId, membership.role() == TenantRole.OWNER, requestId));
        if (!change.agentId().equals(agentId)) {
            throw new BusinessException(
                    "Agent configuration change not found", HttpStatus.NOT_FOUND,
                    "AGENT_CONFIGURATION_CHANGE_NOT_FOUND");
        }
        return change;
    }

    public AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView decide(
            String tenantId,
            String actorId,
            String agentId,
            String requestId,
            long expectedRevision,
            AgentConfigurationChangeApplicationApi.Decision decision,
            String note) {
        TenantMembershipView membership = activeMembership(tenantId, actorId);
        if (membership.role() != TenantRole.OWNER) {
            throw forbidden("Only the current Organization owner may decide Agent changes",
                    "AGENT_CONFIGURATION_OWNER_APPROVAL_REQUIRED");
        }
        return changes.decide(new AgentConfigurationChangeApplicationApi.DecisionCommand(
                tenantId, actorId, agentId, requestId, expectedRevision, decision, note));
    }

    private TenantMembershipView activeMembership(String tenantId, String actorId) {
        return identity.findTenantMembership(tenantId, actorId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> forbidden("Active Organization membership is required",
                        "AGENT_CONFIGURATION_MEMBERSHIP_REQUIRED"));
    }

    private AgentDefinitionView requireTenantAgent(String tenantId, String agentId) {
        return agents.findById(agentId)
                .filter(value -> value.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "Agent not found", HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND"));
    }

    private static OrganizationAgentAccessView access(
            AgentDefinitionView agent, String actorId, TenantRole role) {
        WriteMode mode;
        if (role == TenantRole.VIEWER) {
            mode = WriteMode.READ_ONLY;
        } else if (agent.ownerId().equals(actorId) || role == TenantRole.OWNER) {
            mode = WriteMode.DIRECT;
        } else {
            mode = WriteMode.OWNER_APPROVAL_REQUIRED;
        }
        return new OrganizationAgentAccessView(agent, mode);
    }

    private static BusinessException forbidden(String message, String code) {
        return new BusinessException(message, HttpStatus.FORBIDDEN, code);
    }

    public enum SaveOutcome {
        APPLIED,
        PENDING_APPROVAL
    }

    public record SaveResult(
            SaveOutcome outcome,
            AgentDefinitionView agent,
            AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView changeRequest) {
    }

    public enum WriteMode {
        DIRECT,
        OWNER_APPROVAL_REQUIRED,
        READ_ONLY
    }

    public record OrganizationAgentAccessView(AgentDefinitionView agent, WriteMode writeMode) {
    }
}
