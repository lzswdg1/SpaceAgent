package com.spaceagent.platform.integration.infrastructure.persistence;

import com.spaceagent.platform.integration.domain.PlatformSystemAdministrationCommandRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresPlatformSystemAdministrationCommandRepository
        implements PlatformSystemAdministrationCommandRepository {
    private final JdbcTemplate jdbc;
    public PostgresPlatformSystemAdministrationCommandRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean hasSuccessfulOrganizationDeletion(String organizationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_identity_admin_commands
                  WHERE operation='ORGANIZATION_DELETE' AND target_user_id=? AND state='SUCCEEDED')
                """,Boolean.class,organizationId));
    }

    @Override public boolean insert(CommandRecord command) {
        return jdbc.update("""
                INSERT INTO platform_identity_admin_commands (
                    command_id, idempotency_hash, operation, request_hash, actor_id,
                    target_user_id, state, result_json, safe_error_code,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, command.commandId(), command.idempotencyHash(), command.operation(),
                command.requestHash(), command.actorId(), command.targetUserId(), command.state(),
                command.resultJson(), command.safeErrorCode(), Timestamp.from(command.createdAt()),
                Timestamp.from(command.updatedAt()), timestamp(command.completedAt())) == 1;
    }

    @Override public Optional<CommandRecord> findById(UUID commandId) {
        return jdbc.query("SELECT * FROM platform_identity_admin_commands WHERE command_id = ?",
                this::map, commandId).stream().findFirst();
    }

    @Override public Optional<CommandRecord> findByOperationAndIdempotencyHash(String operation, String hash) {
        return jdbc.query("""
                SELECT * FROM platform_identity_admin_commands
                WHERE operation = ? AND idempotency_hash = ?
                """, this::map, operation, hash).stream().findFirst();
    }

    @Override public void complete(UUID commandId, String state, String resultJson,
                                   String safeErrorCode, Instant at) {
        jdbc.update("""
                UPDATE platform_identity_admin_commands
                   SET state = ?, result_json = CAST(? AS JSONB), safe_error_code = ?,
                       updated_at = ?, completed_at = ?
                 WHERE command_id = ? AND state = 'RECEIVED'
                """, state, resultJson, safeErrorCode, Timestamp.from(at), Timestamp.from(at), commandId);
    }

    private CommandRecord map(ResultSet rs, int row) throws SQLException {
        return new CommandRecord(rs.getObject("command_id", UUID.class),
                rs.getString("idempotency_hash"), rs.getString("operation"),
                rs.getString("request_hash"), rs.getObject("actor_id", UUID.class),
                rs.getString("target_user_id"), rs.getString("state"),
                rs.getString("result_json"), rs.getString("safe_error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
