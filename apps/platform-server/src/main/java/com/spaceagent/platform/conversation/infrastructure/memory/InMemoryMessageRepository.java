package com.spaceagent.platform.conversation.infrastructure.memory;

import com.spaceagent.platform.conversation.domain.Message;
import com.spaceagent.platform.conversation.domain.MessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory message repository for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMessageRepository implements MessageRepository {

    private final Map<String, Message> messages = new ConcurrentHashMap<>();

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return messages.values().stream()
                .filter(message -> conversationId.equals(message.conversationId()))
                .filter(message -> !message.role().equals("ASSISTANT_PENDING"))
                .sorted(java.util.Comparator.comparingInt(Message::sequence))
                .toList();
    }

    @Override
    public List<Message> findRecentByConversationId(String conversationId, int limit) {
        List<Message> ordered = findByConversationId(conversationId);
        return ordered.subList(Math.max(0, ordered.size() - limit), ordered.size());
    }

    @Override
    public Optional<Message> findById(String id) {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public void save(Message message) {
        synchronized (messages) {
            boolean sequenceExists = messages.values().stream().anyMatch(existing ->
                    existing.conversationId().equals(message.conversationId())
                            && existing.sequence() == message.sequence()
                            && !existing.id().equals(message.id()));
            if (sequenceExists) {
                throw new IllegalStateException("Conversation message sequence already exists");
            }
            messages.put(message.id(), message);
        }
    }

    @Override
    public int nextSequence(String conversationId) {
        synchronized (messages) {
            return messages.values().stream()
                    .filter(message -> conversationId.equals(message.conversationId()))
                    .mapToInt(Message::sequence)
                    .max().orElse(-1) + 1;
        }
    }

    @Override
    public boolean completeReserved(String messageId, String content, java.time.Instant completedAt) {
        synchronized (messages) {
            Message current = messages.get(messageId);
            if (current == null || !("ASSISTANT_PENDING".equals(current.role()) || "ASSISTANT_PARTIAL".equals(current.role()))) return false;
            messages.put(messageId, new Message(
                    current.id(), current.conversationId(), current.sequence(),
                    "ASSISTANT", content, completedAt));
            return true;
        }
    }

    @Override
    public long countByConversationId(String conversationId) {
        return messages.values().stream()
                .filter(message -> conversationId.equals(message.conversationId()))
                .filter(message -> !"ASSISTANT_PENDING".equals(message.role()))
                .count();
    }

    @Override public boolean updateReservedDraft(String messageId,String content,java.time.Instant at) {
        synchronized(messages) {
            Message current=messages.get(messageId);
            if(current==null || !(current.role().equals("ASSISTANT_PENDING")||current.role().equals("ASSISTANT_PARTIAL")))return false;
            messages.put(messageId,new Message(current.id(),current.conversationId(),current.sequence(),"ASSISTANT_PARTIAL",content,at));
            return true;
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        messages.values().removeIf(message -> conversationId.equals(message.conversationId()));
    }
}
