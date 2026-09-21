package com.spaceagent.platform.conversation.domain;

import java.util.Optional;

/**
 * Domain port for conversation context snapshot persistence.
 */
public interface ConversationContextSnapshotRepository {
    Optional<ConversationContextSnapshot> findById(String id);

    Optional<ConversationContextSnapshot> findLatestByConversationId(String conversationId);

    ConversationContextSnapshot save(ConversationContextSnapshot snapshot);
}
