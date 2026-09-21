package com.spaceagent.platform.conversation.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for conversation persistence.
 */
public interface ConversationRepository {
    Optional<Conversation> findById(String id);

    Optional<Conversation> findByIdForUpdate(String id);

    List<Conversation> findByTenantAndUserId(
            String tenantId, String userId, int limit, int offset);

    long countByTenantAndUserId(String tenantId, String userId);

    default List<Conversation> findFiltered(String tenantId, String userId, String scope, String query, int limit, int offset) {
        return findByTenantAndUserId(tenantId,userId,Integer.MAX_VALUE,0).stream()
                .filter(value -> matches(value,scope,query))
                .sorted(java.util.Comparator.comparing(Conversation::updatedAt).thenComparing(Conversation::id).reversed())
                .skip(offset).limit(limit).toList();
    }
    default long countFiltered(String tenantId,String userId,String scope,String query) {
        return findFiltered(tenantId,userId,scope,query,Integer.MAX_VALUE,0).size();
    }
    private static boolean matches(Conversation value,String scope,String query) {
        return (scope.equals("ALL") || scope.equals("PROJECT") == (value.projectId()!=null))
                && value.title().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }

    List<Conversation> findByProjectDirectoryId(
            String tenantId, String userId, String projectDirectoryId, int limit, int offset);

    long countByProjectDirectoryId(String tenantId, String userId, String projectDirectoryId);

    void save(Conversation conversation);

    void deleteById(String id);
}
