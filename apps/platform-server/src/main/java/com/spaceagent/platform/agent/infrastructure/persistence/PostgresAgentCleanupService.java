package com.spaceagent.platform.agent.infrastructure.persistence;

import com.spaceagent.platform.agent.api.AgentCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentCleanupService implements AgentCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresAgentCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("""
                DELETE FROM platform_agent_api_keys key
                USING platform_agent_definitions agent
                WHERE key.agent_id = agent.id AND agent.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                DELETE FROM platform_agent_knowledge_bindings binding
                USING platform_agent_definitions agent
                WHERE binding.agent_id = agent.id AND agent.tenant_id = ?
                """, organizationId);
        jdbc.update("DELETE FROM platform_agent_definitions WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional(readOnly = true)
    public void cleanupUser(String userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM platform_agent_definitions WHERE owner_id = ?", Long.class, userId);
        if (count != null && count > 0L) throw new IllegalStateException("USER_OWNS_AGENTS");
    }
}
