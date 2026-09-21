package com.spaceagent.platform.conversation.infrastructure.memory;

import com.spaceagent.platform.conversation.domain.ConversationContextSnapshot;
import com.spaceagent.platform.conversation.domain.ConversationContextSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation context snapshot repository for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationContextSnapshotRepository
        implements ConversationContextSnapshotRepository {

    private final Map<String, ConversationContextSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<ConversationContextSnapshot> findById(String id) {
        return Optional.ofNullable(snapshots.get(id));
    }

    @Override
    public Optional<ConversationContextSnapshot> findLatestByConversationId(
            String conversationId) {
        return snapshots.values().stream()
                .filter(value -> value.conversationId().equals(conversationId))
                .max(Comparator.comparingInt(ConversationContextSnapshot::version));
    }

    @Override
    public synchronized ConversationContextSnapshot save(ConversationContextSnapshot snapshot) {
        ConversationContextSnapshot existing = snapshots.values().stream()
                .filter(value -> snapshot.conversationId().equals(value.conversationId()))
                .filter(value -> snapshot.version() == value.version())
                .findFirst().orElse(null);
        if (existing != null) return existing;
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }
}
