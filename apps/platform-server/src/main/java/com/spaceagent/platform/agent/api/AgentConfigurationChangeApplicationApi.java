package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;

import java.time.Instant;
import java.util.List;

/** Public Agent-owned use cases for temporary cross-owner configuration proposals. */
public interface AgentConfigurationChangeApplicationApi {

    AgentConfigurationChangeRequestView request(UpdateAgentDefinitionCommand command);

    AgentConfigurationChangeRequestView decide(DecisionCommand command);

    AgentConfigurationChangeRequestView get(GetQuery query);

    List<AgentConfigurationChangeRequestView> list(ListQuery query);

    record DecisionCommand(
            String tenantId,
            String organizationOwnerId,
            String agentId,
            String requestId,
            long expectedRevision,
            Decision decision,
            String note) {
    }

    enum Decision {
        APPROVE,
        REJECT
    }

    record GetQuery(String tenantId, String actorId, boolean organizationOwner, String requestId) {
    }

    record ListQuery(
            String tenantId,
            String actorId,
            boolean organizationOwner,
            String agentId,
            int offset,
            int limit) {
    }

    record AgentConfigurationChangeRequestView(
            String id,
            String approvalId,
            String tenantId,
            String agentId,
            String agentOwnerId,
            String requestedBy,
            long baseAgentRevision,
            String baseConfigHash,
            String proposalHash,
            ProposalView proposal,
            AgentConfigurationChangeState state,
            String closedBy,
            String decisionNote,
            Instant closedAt,
            Long appliedAgentRevision,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
    }

record ProposalView(
            String name,
            String description,
            String systemPrompt,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            double temperature,
            int maxContextTokens,
            int maxOutputTokens,
            int maxTurns,
            String permissionMode,
            boolean memoryEnabled,
            boolean ragEnabled,
            boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String configHash,
        List<String> knowledgeCollectionIds) {
        /** Source compatibility: legacy IDs remain document IDs. */
        public ProposalView(
                String name,
                String description,
                String systemPrompt,
                String modelPoolId,
                String modelProviderId,
                String modelId,
                double temperature,
                int maxContextTokens,
                int maxOutputTokens,
                int maxTurns,
                String permissionMode,
                boolean memoryEnabled,
                boolean ragEnabled,
                boolean networkEnabled,
                List<String> knowledgeBaseIds,
                List<String> enabledToolIds,
                List<String> skillIds,
                String configHash) {
            this(
                    name, description, systemPrompt, modelPoolId, modelProviderId,
                    modelId, temperature, maxContextTokens, maxOutputTokens, maxTurns,
                    permissionMode, memoryEnabled, ragEnabled, networkEnabled, knowledgeBaseIds,
                    enabledToolIds, skillIds, configHash, List.of());
        }

    }
}
