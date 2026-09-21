package com.spaceagent.platform.agent.application;

import com.spaceagent.platform.agent.api.AgentConfigurationApprovalPort;
import com.spaceagent.platform.agent.api.AgentConfigurationChangeApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequest;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequestRepository;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AgentConfigurationChangeApplicationService
        implements AgentConfigurationChangeApplicationApi {

    private final AgentConfigurationChangeRequestRepository requests;
    private final AgentApplicationService agents;
    private final AgentConfigurationFactory configurationFactory;
    private final AgentConfigurationApprovalPort approvals;
    private final IdGenerator ids;
    private final TimeProvider time;

    public AgentConfigurationChangeApplicationService(
            AgentConfigurationChangeRequestRepository requests,
            AgentApplicationService agents,
            AgentConfigurationFactory configurationFactory,
            AgentConfigurationApprovalPort approvals,
            IdGenerator ids,
            TimeProvider time) {
        this.requests = requests;
        this.agents = agents;
        this.configurationFactory = configurationFactory;
        this.approvals = approvals;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public AgentConfigurationChangeRequestView request(UpdateAgentDefinitionCommand command) {
        AgentApplicationService.PreparedAgentChange prepared = agents.prepareChange(command);
        if (prepared.definition().ownerId().equals(command.ownerId())) {
            throw new BusinessException("Agent creator changes must be saved directly",
                    HttpStatus.CONFLICT, "AGENT_CONFIGURATION_DIRECT_SAVE_REQUIRED");
        }
        AgentConfigurationProposal proposal = prepared.proposal();
        String proposalHash = configurationFactory.proposalHash(proposal);
        AgentConfigurationChangeRequest existing = requests.findPending(
                prepared.definition().tenantId(), prepared.definition().id(), command.ownerId())
                .orElse(null);
        if (existing != null && existing.sameProposal(proposalHash)
                && existing.baseAgentRevision() == prepared.definition().revision()
                && existing.baseConfigHash().equals(prepared.previousConfiguration().configHash())) {
            return view(existing);
        }

        String summary = "Update Agent " + prepared.definition().name();
        if (existing == null) {
            AgentConfigurationApprovalPort.ApprovalReference approval = approvals.requestRequired(
                    prepared.definition().tenantId(), command.ownerId(), prepared.definition().id(),
                    proposalHash, summary);
            AgentConfigurationChangeRequest created = AgentConfigurationChangeRequest.pending(
                    ids.nextId(), approval.id(), prepared.definition().tenantId(),
                    prepared.definition().id(), prepared.definition().ownerId(), command.ownerId(),
                    prepared.definition().revision(), prepared.previousConfiguration().configHash(),
                    proposalHash, proposal, time.now());
            requests.insert(created);
            return view(created);
        }

        AgentConfigurationApprovalPort.ApprovalReference approval = approvals.replaceRequired(
                existing.approvalId(), prepared.definition().tenantId(), command.ownerId(),
                prepared.definition().id(), proposalHash, summary);
        AgentConfigurationChangeRequest replacement = existing.replacePending(
                approval.id(), prepared.definition().revision(),
                prepared.previousConfiguration().configHash(), proposalHash, proposal, time.now());
        return view(requests.update(
                        replacement, existing.revision(), AgentConfigurationChangeState.PENDING)
                .orElseThrow(() -> conflict(
                        "Agent configuration proposal changed concurrently",
                        "AGENT_CONFIGURATION_CHANGE_REVISION_CONFLICT")));
    }

    @Override
    public AgentConfigurationChangeRequestView decide(DecisionCommand command) {
        AgentConfigurationChangeRequest request = requests.findByIdForUpdate(
                        command.tenantId(), command.requestId())
                .orElseThrow(() -> notFound(command.requestId()));
        if (!request.agentId().equals(command.agentId())) {
            throw notFound(command.requestId());
        }
        if (request.state() != AgentConfigurationChangeState.PENDING) {
            if ((command.decision() == Decision.APPROVE
                    && (request.state() == AgentConfigurationChangeState.APPLIED
                        || request.state() == AgentConfigurationChangeState.STALE))
                    || (command.decision() == Decision.REJECT
                        && request.state() == AgentConfigurationChangeState.REJECTED)) {
                return view(request);
            }
            throw conflict("Agent configuration change is not pending",
                    "AGENT_CONFIGURATION_CHANGE_NOT_PENDING");
        }
        if (request.revision() != command.expectedRevision()) {
            throw conflict("Agent configuration change revision conflict",
                    "AGENT_CONFIGURATION_CHANGE_REVISION_CONFLICT");
        }

        approvals.decide(command.tenantId(), command.organizationOwnerId(), request.approvalId(),
                command.decision() == Decision.APPROVE, command.note());
        if (command.decision() == Decision.REJECT) {
            return close(request, AgentConfigurationChangeState.REJECTED,
                    command.organizationOwnerId(), command.note(), null);
        }

        AgentDefinitionView applied = agents.applyPreparedChange(
                request.tenantId(), command.organizationOwnerId(), request.agentId(),
                request.baseAgentRevision(), request.baseConfigHash(), request.proposal())
                .orElse(null);
        approvals.consume(request.tenantId(), request.requestedBy(), request.agentId(),
                request.proposalHash(), request.approvalId());
        if (applied == null) {
            return close(request, AgentConfigurationChangeState.STALE,
                    command.organizationOwnerId(), "Agent changed before approval", null);
        }
        return close(request, AgentConfigurationChangeState.APPLIED,
                command.organizationOwnerId(), command.note(), applied.revision());
    }

    @Override
    public AgentConfigurationChangeRequestView get(GetQuery query) {
        AgentConfigurationChangeRequest request = requests.findByIdForUpdate(
                        query.tenantId(), query.requestId())
                .orElseThrow(() -> notFound(query.requestId()));
        request = reconcileApprovalState(request);
        requireVisible(request, query.actorId(), query.organizationOwner());
        return view(request);
    }

    @Override
    public List<AgentConfigurationChangeRequestView> list(ListQuery query) {
        int offset = Math.max(0, query.offset());
        int limit = query.limit() <= 0 ? 50 : Math.min(query.limit(), 100);
        return requests.listByAgent(query.tenantId(), query.agentId(), offset, limit).stream()
                .filter(value -> query.organizationOwner()
                        || value.requestedBy().equals(query.actorId())
                        || value.agentOwnerId().equals(query.actorId()))
                .map(this::reconcileLocked)
                .map(AgentConfigurationChangeApplicationService::view)
                .toList();
    }

    private AgentConfigurationChangeRequest reconcileLocked(
            AgentConfigurationChangeRequest candidate) {
        if (candidate.state() != AgentConfigurationChangeState.PENDING) {
            return candidate;
        }
        AgentConfigurationChangeRequest locked = requests.findByIdForUpdate(
                candidate.tenantId(), candidate.id()).orElse(candidate);
        return reconcileApprovalState(locked);
    }

    private AgentConfigurationChangeRequest reconcileApprovalState(
            AgentConfigurationChangeRequest request) {
        if (request.state() != AgentConfigurationChangeState.PENDING) {
            return request;
        }
        String state = approvals.status(
                request.tenantId(), request.requestedBy(), request.approvalId()).state();
        AgentConfigurationChangeState terminal = switch (state) {
            case "EXPIRED" -> AgentConfigurationChangeState.EXPIRED;
            case "CANCELLED" -> AgentConfigurationChangeState.SUPERSEDED;
            default -> null;
        };
        if (terminal == null) {
            return request;
        }
        AgentConfigurationChangeRequest closed = request.close(
                terminal, terminal == AgentConfigurationChangeState.SUPERSEDED
                        ? request.requestedBy() : null,
                null, null, time.now());
        return requests.update(closed, request.revision(), AgentConfigurationChangeState.PENDING)
                .orElseGet(() -> requests.findById(request.tenantId(), request.id()).orElse(request));
    }

    private AgentConfigurationChangeRequestView close(
            AgentConfigurationChangeRequest request,
            AgentConfigurationChangeState state,
            String actor,
            String note,
            Long appliedRevision) {
        AgentConfigurationChangeRequest terminal = request.close(
                state, actor, note, appliedRevision, time.now());
        return view(requests.update(
                        terminal, request.revision(), AgentConfigurationChangeState.PENDING)
                .orElseThrow(() -> conflict(
                        "Agent configuration change revision conflict",
                        "AGENT_CONFIGURATION_CHANGE_REVISION_CONFLICT")));
    }

    private static void requireVisible(
            AgentConfigurationChangeRequest request,
            String actorId,
            boolean organizationOwner) {
        if (!organizationOwner && !request.requestedBy().equals(actorId)
                && !request.agentOwnerId().equals(actorId)) {
            throw notFound(request.id());
        }
    }

    private static AgentConfigurationChangeRequestView view(
            AgentConfigurationChangeRequest value) {
        return new AgentConfigurationChangeRequestView(
                value.id(), value.approvalId(), value.tenantId(), value.agentId(),
                value.agentOwnerId(), value.requestedBy(), value.baseAgentRevision(),
                value.baseConfigHash(), value.proposalHash(), proposal(value.proposal()),
                value.state(), value.closedBy(), value.decisionNote(), value.closedAt(),
                value.appliedAgentRevision(), value.revision(), value.createdAt(), value.updatedAt());
    }

    private static ProposalView proposal(AgentConfigurationProposal value) {
        if (value == null) return null;
        return new ProposalView(
                value.name(), value.description(), value.systemPrompt(), value.modelPoolId(),
                value.modelProviderId(), value.modelId(), value.temperature(),
                value.maxContextTokens(), value.maxOutputTokens(), value.maxTurns(),
                value.permissionMode(), value.memoryEnabled(), value.ragEnabled(),
                value.networkEnabled(), value.knowledgeBaseIds(), value.enabledToolIds(),
                value.skillIds(), value.configHash(), value.knowledgeCollectionIds());
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static BusinessException notFound(String requestId) {
        return new BusinessException("Agent configuration change not found: " + requestId,
                HttpStatus.NOT_FOUND, "AGENT_CONFIGURATION_CHANGE_NOT_FOUND");
    }
}
