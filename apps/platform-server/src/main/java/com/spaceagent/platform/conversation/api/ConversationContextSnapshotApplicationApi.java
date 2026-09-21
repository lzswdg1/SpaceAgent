package com.spaceagent.platform.conversation.api;

import java.util.Optional;

/**
 * Public conversation-context snapshot application API. Conversation owns durable
 * snapshot state; runtime consumes this API without touching conversation
 * persistence.
 */
public interface ConversationContextSnapshotApplicationApi {

    ConversationContextSnapshotView save(SaveConversationContextSnapshotCommand command);

    Optional<ConversationContextSnapshotView> findById(String snapshotId);

    Optional<ConversationContextSnapshotView> findLatestByConversationId(String conversationId);
}
