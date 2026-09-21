package com.spaceagent.platform.conversation.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for message persistence.
 */
public interface MessageRepository {
    List<Message> findByConversationId(String conversationId);

    List<Message> findRecentByConversationId(String conversationId, int limit);

    default List<Message> findBeforeSequence(String conversationId, int beforeSequence, int limit) {
        return findByConversationId(conversationId).stream().filter(message -> message.sequence()<beforeSequence
                        && !message.role().equals("ASSISTANT_PENDING"))
                .sorted(java.util.Comparator.comparingInt(Message::sequence).reversed()).limit(limit).toList();
    }

    Optional<Message> findById(String id);

    void save(Message message);

    int nextSequence(String conversationId);

    boolean completeReserved(String messageId, String content, java.time.Instant completedAt);
    default boolean updateReservedDraft(String messageId,String content,java.time.Instant at) { return false; }

    long countByConversationId(String conversationId);

    void deleteByConversationId(String conversationId);
}
