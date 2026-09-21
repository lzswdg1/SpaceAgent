package com.spaceagent.platform.memory.infrastructure.persistence;

import com.spaceagent.platform.memory.domain.MemorySystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMemorySystemAdministrationQuery implements MemorySystemAdministrationQuery {
    private static final String MEMORIES = """
            WITH user_memories AS (
              SELECT candidate.id, candidate.state,
                     candidate.kind || ':CANDIDATE' relation,
                     candidate.created_at, candidate.created_at updated_at
                FROM platform_memory_candidates candidate
               WHERE candidate.scope_type = 'USER' AND candidate.scope_id = ?
              UNION ALL
              SELECT memory.id, 'CONSOLIDATED' state,
                     memory.kind || ':CONSOLIDATED' relation,
                     memory.created_at, memory.updated_at
                FROM platform_consolidated_memories memory
               WHERE memory.scope_type = 'USER' AND memory.scope_id = ?
            )
            """;
    private final JdbcTemplate jdbc;

    public PostgresMemorySystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public PageRows<ResourceRow> memoriesByUser(
            String userId, int offset, int limit) {
        var items = jdbc.query(MEMORIES + """
                SELECT * FROM user_memories ORDER BY updated_at DESC, id LIMIT ? OFFSET ?
                """, (rs, row) -> new ResourceRow(rs.getString("id"), rs.getString("state"),
                rs.getString("relation"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), userId, userId, limit, offset);
        Long total = jdbc.queryForObject(MEMORIES + "SELECT count(*) FROM user_memories",
                Long.class, userId, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }
}
