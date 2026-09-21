package com.spaceagent.platform.conversation.infrastructure.memory;

import com.spaceagent.platform.conversation.domain.Conversation;
import com.spaceagent.platform.conversation.domain.ConversationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation repository for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Override
    public Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    @Override
    public Optional<Conversation> findByIdForUpdate(String id) {
        return findById(id);
    }

    @Override
    public List<Conversation> findByTenantAndUserId(
            String tenantId, String userId, int limit, int offset) {
        return conversations.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .sorted(java.util.Comparator.comparing(Conversation::updatedAt).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countByTenantAndUserId(String tenantId, String userId) {
        return conversations.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .count();
    }

    @Override
    public List<Conversation> findByProjectDirectoryId(
            String tenantId, String userId, String projectDirectoryId,
            int limit, int offset) {
        return conversations.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .filter(value -> projectDirectoryId.equals(value.projectDirectoryId()))
                .sorted(java.util.Comparator.comparing(Conversation::updatedAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long countByProjectDirectoryId(
            String tenantId, String userId, String projectDirectoryId) {
        return conversations.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .filter(value -> projectDirectoryId.equals(value.projectDirectoryId()))
                .count();
    }

    @Override
    public void save(Conversation conversation) {
        conversations.put(conversation.id(), conversation);
    }

    @Override
    public void deleteById(String id) {
        conversations.remove(id);
    }
}
