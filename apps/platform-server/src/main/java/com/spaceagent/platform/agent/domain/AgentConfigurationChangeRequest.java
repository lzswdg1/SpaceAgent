package com.spaceagent.platform.agent.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Temporary Organization change proposal. Terminal records retain only bounded audit hashes;
 * proposal content is deliberately erased so this cannot become Agent version history.
 */
public record AgentConfigurationChangeRequest(
        String id,
        String approvalId,
        String tenantId,
        String agentId,
        String agentOwnerId,
        String requestedBy,
        long baseAgentRevision,
        String baseConfigHash,
        String proposalHash,
        AgentConfigurationProposal proposal,
        AgentConfigurationChangeState state,
        String closedBy,
        String decisionNote,
        Instant closedAt,
        Long appliedAgentRevision,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AgentConfigurationChangeRequest {
        id = requireText(id, "id");
        approvalId = requireText(approvalId, "approvalId");
        tenantId = requireText(tenantId, "tenantId");
        agentId = requireText(agentId, "agentId");
        agentOwnerId = requireText(agentOwnerId, "agentOwnerId");
        requestedBy = requireText(requestedBy, "requestedBy");
        if (agentOwnerId.equals(requestedBy)) {
            throw new IllegalArgumentException("Agent owner changes must be applied directly");
        }
        if (baseAgentRevision <= 0 || revision <= 0) {
            throw new IllegalArgumentException("Agent change revisions must be positive");
        }
        AgentConfigurationProposal.requireHash(baseConfigHash, "baseConfigHash", false);
        AgentConfigurationProposal.requireHash(proposalHash, "proposalHash", true);
        state = Objects.requireNonNull(state, "state");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        decisionNote = bounded(decisionNote, 2_000);
        if (state == AgentConfigurationChangeState.PENDING) {
            Objects.requireNonNull(proposal, "proposal");
            if (closedBy != null || decisionNote != null || closedAt != null
                    || appliedAgentRevision != null) {
                throw new IllegalArgumentException("Pending Agent change cannot contain terminal evidence");
            }
        } else {
            if (proposal != null || closedAt == null) {
                throw new IllegalArgumentException("Terminal Agent change must erase proposal content");
            }
            if (state == AgentConfigurationChangeState.APPLIED) {
                if (closedBy == null || appliedAgentRevision == null
                        || appliedAgentRevision <= baseAgentRevision) {
                    throw new IllegalArgumentException("Applied Agent change evidence is invalid");
                }
            } else if (appliedAgentRevision != null) {
                throw new IllegalArgumentException("Only an applied Agent change has an applied revision");
            }
        }
    }

    public static AgentConfigurationChangeRequest pending(
            String id,
            String approvalId,
            String tenantId,
            String agentId,
            String agentOwnerId,
            String requestedBy,
            long baseAgentRevision,
            String baseConfigHash,
            String proposalHash,
            AgentConfigurationProposal proposal,
            Instant now) {
        return new AgentConfigurationChangeRequest(
                id, approvalId, tenantId, agentId, agentOwnerId, requestedBy,
                baseAgentRevision, baseConfigHash, proposalHash, proposal,
                AgentConfigurationChangeState.PENDING, null, null, null, null,
                1, now, now);
    }

    public AgentConfigurationChangeRequest replacePending(
            String nextApprovalId,
            long nextBaseAgentRevision,
            String nextBaseConfigHash,
            String nextProposalHash,
            AgentConfigurationProposal nextProposal,
            Instant now) {
        requirePending();
        return new AgentConfigurationChangeRequest(
                id, nextApprovalId, tenantId, agentId, agentOwnerId, requestedBy,
                nextBaseAgentRevision, nextBaseConfigHash, nextProposalHash, nextProposal,
                state, null, null, null, null, revision + 1, createdAt, now);
    }

    public AgentConfigurationChangeRequest close(
            AgentConfigurationChangeState terminalState,
            String actorId,
            String note,
            Long appliedRevision,
            Instant now) {
        requirePending();
        if (terminalState == null || !terminalState.terminal()) {
            throw new IllegalArgumentException("A terminal Agent change state is required");
        }
        return new AgentConfigurationChangeRequest(
                id, approvalId, tenantId, agentId, agentOwnerId, requestedBy,
                baseAgentRevision, baseConfigHash, proposalHash, null, terminalState,
                normalize(actorId), note, Objects.requireNonNull(now, "now"), appliedRevision,
                revision + 1, createdAt, now);
    }

    public boolean sameProposal(String operationHash) {
        return state == AgentConfigurationChangeState.PENDING
                && proposalHash.equals(operationHash);
    }

    private void requirePending() {
        if (state != AgentConfigurationChangeState.PENDING) {
            throw new IllegalStateException("Agent change request is not pending");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String bounded(String value, int maximum) {
        String normalized = normalize(value);
        if (normalized != null && normalized.length() > maximum) {
            throw new IllegalArgumentException("decisionNote is too long");
        }
        return normalized;
    }
}
