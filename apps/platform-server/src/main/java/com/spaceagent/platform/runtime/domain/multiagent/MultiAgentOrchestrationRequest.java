package com.spaceagent.platform.runtime.domain.multiagent;

import java.util.List;
import java.util.Objects;

/** Framework-neutral Java representation of contracts/multi-agent/v1 request. */
public record MultiAgentOrchestrationRequest(
        String contractVersion,
        String requestId,
        String agentRunId,
        String organizationId,
        String userId,
        Mode mode,
        String projectId,
        String taskId,
        String taskPlanId,
        String conversationId,
        List<AgentRef> agentRefs,
        String modelPoolRef,
        Context context,
        Capabilities capabilities,
        Cursor cursor,
        Limits limits,
        Reasoning reasoning) {

    public static final String CONTRACT_VERSION = "multi-agent/v1";

    public MultiAgentOrchestrationRequest {
        requireVersion(contractVersion);
        requireText(requestId, "requestId");
        requireText(agentRunId, "agentRunId");
        requireText(organizationId, "organizationId");
        requireText(userId, "userId");
        requireText(conversationId, "conversationId");
        mode = Objects.requireNonNull(mode, "mode");
        agentRefs = List.copyOf(Objects.requireNonNull(agentRefs, "agentRefs"));
        if (agentRefs.size() > 32) {
            throw new IllegalArgumentException("agentRefs must not exceed 32 entries");
        }
        context = Objects.requireNonNull(context, "context");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        cursor = Objects.requireNonNull(cursor, "cursor");
        limits = Objects.requireNonNull(limits, "limits");
    }

    public MultiAgentOrchestrationRequest(
            String contractVersion,
            String requestId,
            String agentRunId,
            String organizationId,
            String userId,
            Mode mode,
            String projectId,
            String taskId,
            String taskPlanId,
            String conversationId,
            List<AgentRef> agentRefs,
            String modelPoolRef,
            Context context,
            Capabilities capabilities,
            Cursor cursor,
            Limits limits) {
        this(contractVersion, requestId, agentRunId, organizationId, userId, mode,
                projectId, taskId, taskPlanId, conversationId, agentRefs,
                modelPoolRef, context, capabilities, cursor, limits, null);
    }

    public enum Mode { CHAT, PROJECT }

    public record AgentRef(String agentId, String runConfigurationSnapshotId, String role) {
        public AgentRef {
            requireText(agentId, "agentId");
            requireText(role, "role");
            if (role.length() > 80) {
                throw new IllegalArgumentException("role must not exceed 80 characters");
            }
        }
    }

    public record Context(
            String contextPackageId,
            int tokenBudget,
            List<ContextSource> sources) {
        public Context {
            requireText(contextPackageId, "contextPackageId");
            if (tokenBudget <= 0) {
                throw new IllegalArgumentException("tokenBudget must be positive");
            }
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            if (sources.size() > 256) {
                throw new IllegalArgumentException("sources must not exceed 256 entries");
            }
        }
    }

    public record ContextSource(
            String type,
            String sourceId,
            String content,
            int priority) {
        public ContextSource {
            requireText(type, "type");
            requireText(sourceId, "sourceId");
            content = content == null ? "" : content;
            if (content.length() > 32_000) {
                throw new IllegalArgumentException(
                        "context source content must not exceed 32000 characters");
            }
        }
    }

    public record Capabilities(
            List<String> allowedToolNames,
            List<String> allowedSkillIds,
            List<String> allowedMcpServerIds) {
        public Capabilities {
            allowedToolNames = immutableIds(allowedToolNames, "allowedToolNames");
            allowedSkillIds = immutableIds(allowedSkillIds, "allowedSkillIds");
            allowedMcpServerIds = immutableIds(allowedMcpServerIds, "allowedMcpServerIds");
        }
    }

    public record Cursor(
            String phase,
            String checkpointId,
            String planStepId,
            String childTaskId) {
        public Cursor {
            if (!List.of("planning", "execute", "handoff", "review", "awaiting_approval", "complete")
                    .contains(phase)) {
                throw new IllegalArgumentException("unsupported cursor phase: " + phase);
            }
        }
    }

    public record Limits(
            int maxDelegations,
            int maxParallelAgents,
            int maxDepth,
            int remainingTokenBudget) {
        public Limits {
            if (maxDelegations < 0 || maxDelegations > 100) {
                throw new IllegalArgumentException("maxDelegations must be between 0 and 100");
            }
            if (maxParallelAgents < 1 || maxParallelAgents > 32) {
                throw new IllegalArgumentException("maxParallelAgents must be between 1 and 32");
            }
            if (maxDepth < 1 || maxDepth > 32) {
                throw new IllegalArgumentException("maxDepth must be between 1 and 32");
            }
            if (remainingTokenBudget < 0) {
                throw new IllegalArgumentException("remainingTokenBudget must not be negative");
            }
        }
    }

    public enum ReasoningMode { PROVIDER }

    public record Reasoning(
            ReasoningMode mode,
            int round,
            String logicalCallId,
            ReasoningResult result) {
        public Reasoning {
            mode = Objects.requireNonNull(mode, "mode");
            if (round < 0 || round > 1) {
                throw new IllegalArgumentException("reasoning round must be zero or one");
            }
            requireText(logicalCallId, "logicalCallId");
            if (logicalCallId.length() > 200) {
                throw new IllegalArgumentException("logicalCallId must not exceed 200 characters");
            }
            if ((round == 0) != (result == null)) {
                throw new IllegalArgumentException(
                        "reasoning result must be absent only for round zero");
            }
        }
    }

    public record ReasoningResult(
            String content,
            int inputTokens,
            int outputTokens,
            String selectedProviderId,
            String selectedModelId,
            String candidateSnapshotHash) {
        public ReasoningResult {
            requireText(content, "reasoning result content");
            if (content.length() > 32_000) {
                throw new IllegalArgumentException(
                        "reasoning result content must not exceed 32000 characters");
            }
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("reasoning token counts must not be negative");
            }
            requireText(selectedProviderId, "selectedProviderId");
            requireText(selectedModelId, "selectedModelId");
        }
    }

    private static List<String> immutableIds(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field)).stream()
                .peek(value -> requireText(value, field + " item"))
                .toList();
    }

    private static void requireVersion(String value) {
        if (!CONTRACT_VERSION.equals(value)) {
            throw new IllegalArgumentException("contractVersion must be " + CONTRACT_VERSION);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
