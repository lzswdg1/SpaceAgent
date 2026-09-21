package com.spaceagent.platform.runtime.api;

import java.util.List;

public record ChatExecutionView(
        String conversationId,
        String agentRunId,
        String assistantMessage,
        boolean memoryUpdated,
        String memoryCandidateId,
        int recalledMemoryCount,
        List<String> recalledMemories,
        boolean ragUsed,
        int retrievedChunkCount,
        List<String> citations,
        int inputTokenCount,
        int outputTokenCount,
        List<ChatRuntimeEvent> events,
        String reasoningContent,
        String executionState,
        String pendingApprovalId,
        String pendingToolCallId,
        String pendingToolName,
        Long pendingToolRevision,
        String rootTaskId,
        String rootTaskState,
        String taskPlanId,
        String taskPlanState) {

    public ChatExecutionView {
        recalledMemories = recalledMemories == null ? List.of() : List.copyOf(recalledMemories);
        citations = citations == null ? List.of() : List.copyOf(citations);
        events = events == null ? List.of() : List.copyOf(events);
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        executionState = executionState == null || executionState.isBlank()
                ? "COMPLETED" : executionState;
    }

    public ChatExecutionView(
            String conversationId, String agentRunId, String assistantMessage,
            boolean memoryUpdated, String memoryCandidateId, int recalledMemoryCount,
            List<String> recalledMemories, boolean ragUsed, int retrievedChunkCount,
            List<String> citations, int inputTokenCount, int outputTokenCount,
            List<ChatRuntimeEvent> events, String reasoningContent, String executionState,
            String pendingApprovalId, String pendingToolCallId, String pendingToolName,
            Long pendingToolRevision, String rootTaskId, String rootTaskState) {
        this(conversationId, agentRunId, assistantMessage, memoryUpdated, memoryCandidateId,
                recalledMemoryCount, recalledMemories, ragUsed, retrievedChunkCount, citations,
                inputTokenCount, outputTokenCount, events, reasoningContent, executionState,
                pendingApprovalId, pendingToolCallId, pendingToolName, pendingToolRevision,
                rootTaskId, rootTaskState, null, null);
    }

    public ChatExecutionView(
            String conversationId,
            String agentRunId,
            String assistantMessage,
            boolean memoryUpdated,
            String memoryCandidateId,
            int recalledMemoryCount,
            List<String> recalledMemories,
            boolean ragUsed,
            int retrievedChunkCount,
            List<String> citations,
            int inputTokenCount,
            int outputTokenCount,
            List<ChatRuntimeEvent> events) {
        this(conversationId, agentRunId, assistantMessage, memoryUpdated,
                memoryCandidateId, recalledMemoryCount, recalledMemories, ragUsed,
                retrievedChunkCount, citations, inputTokenCount, outputTokenCount,
                events, "", "COMPLETED", null, null, null, null, null, null);
    }

    public ChatExecutionView(
            String conversationId,
            String agentRunId,
            String assistantMessage,
            boolean memoryUpdated,
            String memoryCandidateId,
            int recalledMemoryCount,
            List<String> recalledMemories,
            boolean ragUsed,
            int retrievedChunkCount,
            List<String> citations,
            int inputTokenCount,
            int outputTokenCount,
            List<ChatRuntimeEvent> events,
            String reasoningContent) {
        this(conversationId, agentRunId, assistantMessage, memoryUpdated,
                memoryCandidateId, recalledMemoryCount, recalledMemories, ragUsed,
                retrievedChunkCount, citations, inputTokenCount, outputTokenCount,
                events, reasoningContent, "COMPLETED", null, null, null, null,
                null, null);
    }

    public boolean waitingForApproval() {
        return "WAITING_APPROVAL".equals(executionState);
    }

    public boolean waitingForReconciliation() {
        return "WAITING_RECONCILIATION".equals(executionState);
    }

    public boolean waitingForPlanApproval() {
        return "WAITING_PLAN_APPROVAL".equals(executionState);
    }

    public boolean waiting() {
        return waitingForApproval() || waitingForReconciliation() || waitingForPlanApproval();
    }
}
