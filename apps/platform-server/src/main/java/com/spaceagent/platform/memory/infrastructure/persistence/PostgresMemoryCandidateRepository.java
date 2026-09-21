package com.spaceagent.platform.memory.infrastructure.persistence;

import com.spaceagent.platform.memory.domain.MemoryCandidate;
import com.spaceagent.platform.memory.domain.MemoryCandidateRepository;
import com.spaceagent.platform.memory.domain.MemoryCandidateState;
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
 * Authoritative PostgreSQL persistence for memory candidates.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMemoryCandidateRepository implements MemoryCandidateRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresMemoryCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<MemoryCandidate> findByScope(MemoryScopeRef scope) {
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, source_id, source_type, content,
                       confidence, dedupe_key, state, created_at
                FROM platform_memory_candidates
                WHERE scope_type = ? AND scope_id = ?
                ORDER BY created_at
                """, this::map, scope.scope().name(), scope.scopeId());
    }

    @Override
    public List<MemoryCandidate> findByScopeAndState(MemoryScopeRef scope, MemoryCandidateState state) {
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, source_id, source_type, content,
                       confidence, dedupe_key, state, created_at
                FROM platform_memory_candidates
                WHERE scope_type = ? AND scope_id = ? AND state = ?
                ORDER BY created_at
                """, this::map, scope.scope().name(), scope.scopeId(), state.name());
    }

    @Override
    public Optional<MemoryCandidate> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, scope_type, scope_id, kind, source_id, source_type, content,
                       confidence, dedupe_key, state, created_at
                FROM platform_memory_candidates
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override
    public void save(MemoryCandidate candidate) {
        jdbcTemplate.update("""
                INSERT INTO platform_memory_candidates (
                    id, scope_type, scope_id, kind, source_id, source_type, content,
                    confidence, dedupe_key, state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    scope_type = EXCLUDED.scope_type,
                    scope_id = EXCLUDED.scope_id,
                    kind = EXCLUDED.kind,
                    source_id = EXCLUDED.source_id,
                    source_type = EXCLUDED.source_type,
                    content = EXCLUDED.content,
                    confidence = EXCLUDED.confidence,
                    dedupe_key = EXCLUDED.dedupe_key,
                    state = EXCLUDED.state,
                    created_at = EXCLUDED.created_at
                """,
                candidate.id(),
                candidate.scope().scope().name(),
                candidate.scope().scopeId(),
                candidate.kind().name(),
                candidate.sourceId(),
                candidate.sourceType(),
                candidate.content(),
                candidate.confidence(),
                candidate.dedupeKey(),
                candidate.state().name(),
                Timestamp.from(candidate.createdAt()));
    }

    private MemoryCandidate map(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryCandidate(
                rs.getString("id"),
                new MemoryScopeRef(
                        MemoryScope.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_id")),
                MemoryKind.valueOf(rs.getString("kind")),
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("content"),
                rs.getDouble("confidence"),
                rs.getString("dedupe_key"),
                MemoryCandidateState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant());
    }
}
