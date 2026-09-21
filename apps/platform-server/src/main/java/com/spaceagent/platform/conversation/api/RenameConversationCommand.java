package com.spaceagent.platform.conversation.api;

public record RenameConversationCommand(String tenantId, String userId, String conversationId, String title) { }
