package com.spaceagent.platform.runtime.domain;

import java.util.List;

/** Exact bounded Runtime state required to continue a Chat after a Tool wait. */
public record ChatToolWaitCheckpoint(
        String schema,
        String tenantId,
        String ownerId,
        String agentRunId,
        String conversationId,
        String agentId,
        String runConfigurationSnapshotId,
        String chatStepId,
        String assistantReservationId,
        int assistantSequence,
        String chatTaskId,
        String userMessage,
        ModelSelectionSnapshot modelSelection,
        List<MessageSnapshot> messages,
        List<ToolCallSnapshot> toolCalls,
        int nextToolIndex,
        List<String> toolResults,
        String initialContent,
        int initialInputTokens,
        int initialOutputTokens,
        String initialReasoningContent,
        List<String> recalledMemories,
        List<String> citations,
        String pendingToolStepId,
        String pendingApprovalId,
        Long pendingToolRevision,
        String waitKind,
        PlanProgress planProgress) {

    public static final String APPROVAL_SCHEMA = "chat-approval/v1";
    public static final String UNKNOWN_SCHEMA = "chat-tool-unknown/v1";
    public static final String APPROVAL = "APPROVAL";
    public static final String UNKNOWN = "UNKNOWN";

    public ChatToolWaitCheckpoint {
        if (!APPROVAL_SCHEMA.equals(schema) && !UNKNOWN_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported Chat Tool-wait checkpoint schema");
        }
        waitKind = waitKind == null && APPROVAL_SCHEMA.equals(schema) ? APPROVAL : waitKind;
        if (!APPROVAL.equals(waitKind) && !UNKNOWN.equals(waitKind)) {
            throw new IllegalArgumentException("Unsupported Chat Tool wait kind");
        }
        if (APPROVAL.equals(waitKind)) require(pendingApprovalId, "pendingApprovalId");
        if (UNKNOWN.equals(waitKind) && pendingApprovalId != null) {
            throw new IllegalArgumentException("UNKNOWN wait cannot carry an approval");
        }
        if (UNKNOWN.equals(waitKind)
                && (pendingToolRevision == null || pendingToolRevision <= 0)) {
            throw new IllegalArgumentException("UNKNOWN wait requires the Tool ledger revision");
        }
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(agentRunId, "agentRunId");
        require(conversationId, "conversationId");
        require(agentId, "agentId");
        require(runConfigurationSnapshotId, "runConfigurationSnapshotId");
        require(chatStepId, "chatStepId");
        require(assistantReservationId, "assistantReservationId");
        require(userMessage, "userMessage");
        require(pendingToolStepId, "pendingToolStepId");
        if (assistantSequence < 0 || nextToolIndex < 0) {
            throw new IllegalArgumentException("Chat checkpoint sequence/index is invalid");
        }
        if (modelSelection == null) throw new IllegalArgumentException("modelSelection is required");
        messages = List.copyOf(messages == null ? List.of() : messages);
        toolCalls = List.copyOf(toolCalls == null ? List.of() : toolCalls);
        toolResults = List.copyOf(toolResults == null ? List.of() : toolResults);
        initialContent = initialContent == null ? "" : initialContent;
        recalledMemories = List.copyOf(recalledMemories == null ? List.of() : recalledMemories);
        citations = List.copyOf(citations == null ? List.of() : citations);
        initialReasoningContent = initialReasoningContent == null ? "" : initialReasoningContent;
        if (nextToolIndex >= toolCalls.size()) {
            throw new IllegalArgumentException("Pending Tool index is outside the Tool list");
        }
    }

    public ChatToolWaitCheckpoint(
            String schema, String tenantId, String ownerId, String agentRunId,
            String conversationId, String agentId, String runConfigurationSnapshotId, String chatStepId,
            String assistantReservationId, int assistantSequence, String chatTaskId,
            String userMessage, ModelSelectionSnapshot modelSelection,
            List<MessageSnapshot> messages, List<ToolCallSnapshot> toolCalls, int nextToolIndex,
            List<String> toolResults, String initialContent, int initialInputTokens,
            int initialOutputTokens, String initialReasoningContent,
            List<String> recalledMemories, List<String> citations, String pendingToolStepId,
            String pendingApprovalId, Long pendingToolRevision, String waitKind) {
        this(schema, tenantId, ownerId, agentRunId, conversationId, agentId, runConfigurationSnapshotId,
                chatStepId, assistantReservationId, assistantSequence, chatTaskId, userMessage,
                modelSelection, messages, toolCalls, nextToolIndex, toolResults, initialContent,
                initialInputTokens, initialOutputTokens, initialReasoningContent, recalledMemories,
                citations, pendingToolStepId, pendingApprovalId, pendingToolRevision, waitKind, null);
    }

    public record PlanProgress(
            String taskPlanId,
            String planStepId,
            List<StepResult> completedSteps) {
        public PlanProgress {
            require(taskPlanId, "taskPlanId");
            require(planStepId, "planStepId");
            completedSteps = List.copyOf(completedSteps == null ? List.of() : completedSteps);
        }
    }

    public record StepResult(String planStepId, String stepKey, String content) {
        public StepResult {
            require(planStepId, "planStepId");
            require(stepKey, "stepKey");
            content = content == null ? "" : content;
        }
    }

    public record ModelSelectionSnapshot(
            String modelPoolId,
            List<ModelCandidateSnapshot> candidates,
            String routingStrategy,
            boolean fallbackEnabled,
            String snapshotHash) {
        public ModelSelectionSnapshot {
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
            if (candidates.isEmpty()) throw new IllegalArgumentException("Model candidates are required");
            require(routingStrategy, "routingStrategy");
        }
    }

    public record ModelCandidateSnapshot(
            String memberId,
            String providerId,
            String providerModelId,
            String modelId,
            int priority,
            int weight,
            Integer healthLatencyMs,
            String priceId,
            Long inputMicrosPerMillionTokens,
            Long outputMicrosPerMillionTokens) {
        public ModelCandidateSnapshot {
            require(providerId, "providerId");
            require(modelId, "modelId");
        }
    }

    public record MessageSnapshot(String role, String content) {
        public MessageSnapshot {
            require(role, "role");
            content = content == null ? "" : content;
        }
    }

    public record ToolCallSnapshot(String id, String name, String arguments) {
        public ToolCallSnapshot {
            require(id, "toolCallId");
            require(name, "toolName");
            arguments = arguments == null ? "{}" : arguments;
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
