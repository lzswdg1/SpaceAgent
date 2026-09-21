package com.spaceagent.admin.command.infrastructure.persistence;

import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.command.domain.AdminCommandRepository;
import com.spaceagent.admin.command.domain.AdminCommandState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresAdminCommandRepository implements AdminCommandRepository {
    private static final String COLUMNS = """
            id, idempotency_hash, operation, target_type, target_id, request_hash,
            state, platform_reference, result_json, safe_error_code, created_by,
            created_at, updated_at, completed_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresAdminCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(AdminCommand command) {
        return jdbc.update("""
                INSERT INTO admin_commands (
                    id, idempotency_hash, operation, target_type, target_id,
                    request_hash, state, platform_reference, result_json,
                    safe_error_code, created_by, created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
                ON CONFLICT (operation, idempotency_hash) DO NOTHING
                """, command.id(), command.idempotencyHash(), command.operation(),
                command.targetType(), command.targetId(), command.requestHash(),
                command.state().name(), command.platformReference(), command.resultJson(),
                command.safeErrorCode(), command.createdBy(),
                Timestamp.from(command.createdAt()), Timestamp.from(command.updatedAt()),
                timestamp(command.completedAt())) == 1;
    }

    @Override
    public Optional<AdminCommand> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM admin_commands WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    @Override
    public Optional<AdminCommand> findByOperationAndIdempotencyHash(String operation, String hash) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM admin_commands WHERE operation = ? AND idempotency_hash = ?",
                this::map, operation, hash).stream().findFirst();
    }

    @Override
    public PageResult page(int offset, int limit, String state, String operation, String targetId) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> filters = new ArrayList<>();
        if (state != null) { where.append(" AND state = ? "); filters.add(state); }
        if (operation != null) { where.append(" AND operation = ? "); filters.add(operation); }
        if (targetId != null) { where.append(" AND target_id = ? "); filters.add(targetId); }
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(limit); arguments.add(offset);
        List<AdminCommand> items = jdbc.query("SELECT " + COLUMNS + " FROM admin_commands "
                        + where + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::map, arguments.toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM admin_commands " + where,
                Long.class, filters.toArray());
        return new PageResult(items, total == null ? 0 : total);
    }

    @Override
    public boolean transition(UUID id, AdminCommandState expected, AdminCommandState next,
                              String platformReference, String resultJson, String safeErrorCode,
                              Instant at, boolean completed) {
        return jdbc.update("""
                UPDATE admin_commands SET state = ?, platform_reference = ?,
                       result_json = CAST(? AS JSONB), safe_error_code = ?, updated_at = ?,
                       completed_at = CASE WHEN ? THEN ? ELSE completed_at END
                 WHERE id = ? AND state = ?
                """, next.name(), platformReference, resultJson, safeErrorCode,
                Timestamp.from(at), completed, Timestamp.from(at), id, expected.name()) == 1;
    }

    private AdminCommand map(ResultSet rs, int row) throws SQLException {
        return new AdminCommand(
                rs.getObject("id", UUID.class), rs.getString("idempotency_hash"),
                rs.getString("operation"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("request_hash"),
                AdminCommandState.valueOf(rs.getString("state")),
                rs.getString("platform_reference"), rs.getString("result_json"),
                rs.getString("safe_error_code"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
