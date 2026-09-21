package com.spaceagent.platform.conversation.infrastructure.persistence;

import com.spaceagent.platform.conversation.api.ConversationCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresConversationCleanupService implements ConversationCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresConversationCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("""
                DELETE FROM platform_conversation_context_snapshots snapshot
                WHERE EXISTS (SELECT 1 FROM platform_conversations conversation
                    WHERE conversation.id = snapshot.conversation_id
                      AND conversation.tenant_id = ?)
                """, organizationId);
        jdbc.update("DELETE FROM platform_conversations WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupUser(String userId) {
        jdbc.update("""
                DELETE FROM platform_conversation_context_snapshots snapshot
                WHERE EXISTS (SELECT 1 FROM platform_conversations conversation
                    WHERE conversation.id = snapshot.conversation_id AND conversation.user_id = ?)
                """, userId);
        jdbc.update("DELETE FROM platform_conversations WHERE user_id = ?", userId);
    }
}
