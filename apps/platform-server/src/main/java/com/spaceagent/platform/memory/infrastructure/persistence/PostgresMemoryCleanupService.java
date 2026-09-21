package com.spaceagent.platform.memory.infrastructure.persistence;

import com.spaceagent.platform.memory.api.MemoryCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMemoryCleanupService implements MemoryCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresMemoryCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupProjectAndTaskMemory(List<String> projectIds, List<String> taskIds) {
        for (String id : projectIds) deleteScope("PROJECT", id);
        for (String id : taskIds) deleteScope("TASK", id);
    }

    @Override @Transactional
    public void cleanupUserMemory(String userId) {
        deleteScope("USER", userId);
    }

    private void deleteScope(String type, String id) {
        jdbc.update("DELETE FROM platform_memory_candidates WHERE scope_type = ? AND scope_id = ?", type, id);
        jdbc.update("DELETE FROM platform_consolidated_memories WHERE scope_type = ? AND scope_id = ?", type, id);
    }
}
