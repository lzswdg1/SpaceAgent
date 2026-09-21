package com.spaceagent.platform.memory.api;

public record EvaluateMessageMemoryCommand(
        String userId,
        String conversationId,
        String userMessage,
        String assistantMessage) {

    public EvaluateMessageMemoryCommand {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        userMessage = userMessage == null ? "" : userMessage;
        assistantMessage = assistantMessage == null ? "" : assistantMessage;
    }
}
