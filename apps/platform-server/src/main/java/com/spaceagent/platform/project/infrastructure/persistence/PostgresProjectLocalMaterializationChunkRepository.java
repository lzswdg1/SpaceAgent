package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunkRepository;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunkState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectLocalMaterializationChunkRepository
        implements ProjectLocalMaterializationChunkRepository {
    private static final String COLUMNS = """
            id,session_id,request_id,relative_path,byte_offset,content_length,content_sha256,staging_key,
            state,blocked_code,revision,created_at,updated_at
            """;
    private final JdbcTemplate jdbc;

    public PostgresProjectLocalMaterializationChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProjectLocalMaterializationChunk insertOrGet(ProjectLocalMaterializationChunk value) {
        List<ProjectLocalMaterializationChunk> inserted = jdbc.query("""
                INSERT INTO platform_project_local_materialization_chunks(
                    id,session_id,request_id,relative_path,byte_offset,content_length,content_sha256,staging_key,
                    state,blocked_code,revision,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (session_id,request_id) DO NOTHING
                RETURNING id,session_id,request_id,relative_path,byte_offset,content_length,content_sha256,
                    staging_key,state,blocked_code,revision,created_at,updated_at
                """, this::map, uuid(value.id()), uuid(value.sessionId()), value.requestId(), value.relativePath(),
                value.offset(), value.contentLength(), value.contentSha256(), value.stagingKey(), value.state().name(),
                value.blockedCode(), value.revision(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        ProjectLocalMaterializationChunk existing = findByRequest(value.sessionId(), value.requestId())
                .orElseThrow(() -> new IllegalStateException("Materialization chunk insert was lost"));
        if (!samePayload(existing, value)) {
            throw new IllegalStateException("Materialization chunk idempotency input changed");
        }
        return existing;
    }

    @Override
    public Optional<ProjectLocalMaterializationChunk> findByRequest(String sessionId, String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_local_materialization_chunks"
                        + " WHERE session_id=? AND request_id=?", this::map, uuid(sessionId), requestId)
                .stream().findFirst();
    }

    @Override
    public List<ProjectLocalMaterializationChunk> findBySessionId(String sessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_local_materialization_chunks"
                        + " WHERE session_id=? ORDER BY created_at,id", this::map, uuid(sessionId));
    }

    private ProjectLocalMaterializationChunk map(ResultSet result, int row) throws SQLException {
        return new ProjectLocalMaterializationChunk(
                result.getString("id"), result.getString("session_id"), result.getString("request_id"),
                result.getString("relative_path"), result.getLong("byte_offset"), result.getLong("content_length"),
                result.getString("content_sha256"), result.getString("staging_key"),
                ProjectLocalMaterializationChunkState.valueOf(result.getString("state")), result.getString("blocked_code"),
                result.getLong("revision"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private static boolean samePayload(ProjectLocalMaterializationChunk left, ProjectLocalMaterializationChunk right) {
        return left.sessionId().equals(right.sessionId()) && left.requestId().equals(right.requestId())
                && left.relativePath().equals(right.relativePath()) && left.offset() == right.offset()
                && left.contentLength() == right.contentLength() && left.contentSha256().equals(right.contentSha256())
                && left.stagingKey().equals(right.stagingKey());
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
