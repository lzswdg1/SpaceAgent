package com.spaceagent.platform.conversation.api;

/**
 * Public conversation ownership port exposed to other modules.
 */
public interface ConversationOwnershipPort {
    boolean isParticipant(String conversationId, String principalId);
}
