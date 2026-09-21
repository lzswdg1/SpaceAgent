package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSession;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSessionRepository;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSessionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectLocalMaterializationSessionRepository
        implements ProjectLocalMaterializationSessionRepository {
    private static final String COLUMNS = """
            id,tenant_id,owner_id,project_id,bridge_id,bridge_device_id,bridge_root_handle,request_id,
            manifest_sha256,state,blocked_code,revision,expires_at,created_at,updated_at,completed_at
            """;
    private final JdbcTemplate jdbc;

    public PostgresProjectLocalMaterializationSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProjectLocalMaterializationSession value) {
        jdbc.update("""
                INSERT INTO platform_project_local_materialization_sessions(
                    id,tenant_id,owner_id,project_id,bridge_id,bridge_device_id,bridge_root_handle,request_id,
                    manifest_sha256,state,blocked_code,revision,expires_at,created_at,updated_at,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                uuid(value.id()), value.tenantId(), value.ownerUserId(), uuid(value.projectId()), uuid(value.bridgeId()),
                value.bridgeDeviceId(), value.bridgeRootHandle(), value.requestId(), value.manifestSha256(),
                value.state().name(), value.blockedCode(), value.revision(), Timestamp.from(value.expiresAt()),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()), timestamp(value.completedAt()));
    }

    @Override
    public Optional<ProjectLocalMaterializationSession> findById(
            String tenantId, String ownerUserId, String projectId, String sessionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_local_materialization_sessions"
                        + " WHERE id=? AND tenant_id=? AND owner_id=? AND project_id=?",
                this::map, uuid(sessionId), tenantId, ownerUserId, uuid(projectId)).stream().findFirst();
    }

    @Override
    public Optional<ProjectLocalMaterializationSession> findByRequest(
            String tenantId, String ownerUserId, String projectId, String bridgeId, String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_local_materialization_sessions"
                        + " WHERE tenant_id=? AND owner_id=? AND project_id=? AND bridge_id=? AND request_id=?",
                this::map, tenantId, ownerUserId, uuid(projectId), uuid(bridgeId), requestId).stream().findFirst();
    }

    @Override
    public List<ProjectLocalMaterializationSession> findActiveExpiredBefore(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_local_materialization_sessions"
                        + " WHERE state IN ('OPEN','UPLOADING','VERIFIED') AND expires_at <= ?"
                        + " ORDER BY expires_at,id LIMIT ?",
                this::map, Timestamp.from(now), limit);
    }

    @Override
    public boolean update(
            ProjectLocalMaterializationSession value,
            long expectedRevision,
            ProjectLocalMaterializationSessionState expectedState) {
        return jdbc.update("""
                UPDATE platform_project_local_materialization_sessions SET
                    manifest_sha256=?,state=?,blocked_code=?,revision=?,updated_at=?,completed_at=?
                WHERE id=? AND tenant_id=? AND owner_id=? AND project_id=? AND revision=? AND state=?
                """,
                value.manifestSha256(), value.state().name(), value.blockedCode(), value.revision(),
                Timestamp.from(value.updatedAt()), timestamp(value.completedAt()), uuid(value.id()), value.tenantId(),
                value.ownerUserId(), uuid(value.projectId()), expectedRevision, expectedState.name()) == 1;
    }

    private ProjectLocalMaterializationSession map(ResultSet result, int row) throws SQLException {
        return new ProjectLocalMaterializationSession(
                result.getString("id"), result.getString("tenant_id"), result.getString("owner_id"),
                result.getString("project_id"), result.getString("bridge_id"), result.getString("bridge_device_id"),
                result.getString("bridge_root_handle"), result.getString("request_id"), result.getString("manifest_sha256"),
                ProjectLocalMaterializationSessionState.valueOf(result.getString("state")), result.getString("blocked_code"),
                result.getLong("revision"), result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                instant(result.getTimestamp("completed_at")));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
