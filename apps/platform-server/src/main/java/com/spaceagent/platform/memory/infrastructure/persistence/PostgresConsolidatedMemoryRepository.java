package com.spaceagent.platform.memory.infrastructure.persistence;

import com.spaceagent.platform.memory.domain.ConsolidatedMemory;
import com.spaceagent.platform.memory.domain.ConsolidatedMemoryRepository;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScope;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL persistence for consolidated scoped memory.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresConsolidatedMemoryRepository implements ConsolidatedMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresConsolidatedMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ConsolidatedMemory> findByScope(MemoryScopeRef scope) {
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, memory_key, memory_value, created_at, updated_at
                FROM platform_consolidated_memories
                WHERE scope_type = ? AND scope_id = ?
                ORDER BY updated_at DESC
                """, this::map, scope.scope().name(), scope.scopeId());
    }

    @Override
    public List<ConsolidatedMemory> findRecentByScope(
            MemoryScopeRef scope, List<MemoryKind> kinds, int limit) {
        String kindClause = kinds == null || kinds.isEmpty()
                ? "" : " AND kind IN (" + String.join(",",
                java.util.Collections.nCopies(kinds.size(), "?")) + ")";
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(scope.scope().name());
        arguments.add(scope.scopeId());
        if (kinds != null) kinds.forEach(kind -> arguments.add(kind.name()));
        arguments.add(limit);
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, memory_key, memory_value, created_at, updated_at
                FROM platform_consolidated_memories
                WHERE scope_type = ? AND scope_id = ?%s
                ORDER BY updated_at DESC
                LIMIT ?
                """.formatted(kindClause), this::map, arguments.toArray());
    }

    @Override
    public Optional<ConsolidatedMemory> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, memory_key, memory_value, created_at, updated_at
                FROM platform_consolidated_memories
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override
    public void save(ConsolidatedMemory memory) {
        jdbcTemplate.update("""
                INSERT INTO platform_consolidated_memories (
                    id, scope_type, scope_id, kind, memory_key, memory_value, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scope_type, scope_id, kind, memory_key) DO UPDATE SET
                    id = EXCLUDED.id,
                    memory_value = EXCLUDED.memory_value,
                    updated_at = EXCLUDED.updated_at
                """,
                memory.id(),
                memory.scope().scope().name(),
                memory.scope().scopeId(),
                memory.kind().name(),
                memory.key(),
                memory.value(),
                Timestamp.from(memory.createdAt()),
                Timestamp.from(memory.updatedAt()));
    }

    @Override
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM platform_consolidated_memories WHERE id = ?", id);
    }

    private ConsolidatedMemory map(ResultSet rs, int rowNum) throws SQLException {
        return new ConsolidatedMemory(
                rs.getString("id"),
                new MemoryScopeRef(
                        MemoryScope.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_id")),
                MemoryKind.valueOf(rs.getString("kind")),
                rs.getString("memory_key"),
                rs.getString("memory_value"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
