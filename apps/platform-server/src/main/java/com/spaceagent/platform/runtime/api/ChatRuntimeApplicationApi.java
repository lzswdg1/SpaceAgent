package com.spaceagent.platform.runtime.api;

/**
 * Runtime-owned chat execution facade consumed by HTTP/SSE adapters.
 */
public interface ChatRuntimeApplicationApi {

    ChatExecutionView execute(ChatExecutionCommand command);

    default ChatExecutionView executeStreaming(
            ChatExecutionCommand command,
            ChatStreamObserver observer) {
        ChatExecutionView result = execute(command);
        result.events().forEach(observer::onRuntimeEvent);
        if (!result.reasoningContent().isBlank()) {
            observer.onReasoningDelta(result.reasoningContent());
        }
        if (!result.assistantMessage().isBlank()) {
            observer.onContentDelta(result.assistantMessage());
        }
        return result;
    }

    ChatExecutionView executePrepared(PreparedChatExecutionCommand command);

    ChatExecutionView resumeApproval(ChatApprovalResumeCommand command);

    ChatExecutionView resumePlan(ChatPlanResumeCommand command);

    ChatExecutionView reconcileTool(ChatToolReconciliationCommand command);

    ChatRecoveryView recover(ChatRecoveryCommand command);

    interface ChatStreamObserver {
        void onRuntimeEvent(ChatRuntimeEvent event);
        void onReasoningDelta(String content);
        void onContentDelta(String content);
    }
}
