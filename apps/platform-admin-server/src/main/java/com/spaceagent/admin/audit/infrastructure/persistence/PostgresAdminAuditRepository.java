package com.spaceagent.admin.audit.infrastructure.persistence;

import com.spaceagent.admin.audit.domain.AdminAuditEvent;
import com.spaceagent.admin.audit.domain.AdminAuditRepository;
import com.spaceagent.admin.audit.domain.AdminAuditPage;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresAdminAuditRepository implements AdminAuditRepository {
    private final JdbcTemplate jdbc;

    public PostgresAdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AdminAuditEvent event) {
        jdbc.update("""
                INSERT INTO admin_audit_events (
                    id, actor_id, session_id, action, target_type, target_id,
                    reason, request_id, command_id, input_hash, outcome,
                    safe_error_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.actorId(), event.sessionId(), event.action(),
                event.targetType(), event.targetId(), event.reason(), event.requestId(),
                event.commandId(), event.inputHash(), event.outcome().name(),
                event.safeErrorCode(), Timestamp.from(event.occurredAt()));
    }

    @Override
    public AdminAuditPage page(int offset, int limit, UUID actorId, String action, String targetId) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        if (actorId != null) { where.append(" AND actor_id = ?"); arguments.add(actorId); }
        if (action != null && !action.isBlank()) { where.append(" AND action = ?"); arguments.add(action); }
        if (targetId != null && !targetId.isBlank()) { where.append(" AND target_id = ?"); arguments.add(targetId); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM admin_audit_events" + where,
                Long.class, arguments.toArray());
        arguments.add(limit);
        arguments.add(offset);
        var items = jdbc.query("""
                SELECT id, actor_id, session_id, action, target_type, target_id, reason,
                       request_id, command_id, input_hash, outcome, safe_error_code, occurred_at
                FROM admin_audit_events
                """ + where + " ORDER BY occurred_at DESC, id LIMIT ? OFFSET ?",
                (rs, row) -> new AdminAuditEvent(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("reason"), rs.getString("request_id"),
                        rs.getObject("command_id", UUID.class), rs.getString("input_hash"),
                        AdminAuditOutcome.valueOf(rs.getString("outcome")),
                        rs.getString("safe_error_code"), rs.getTimestamp("occurred_at").toInstant()),
                arguments.toArray());
        return new AdminAuditPage(items, total == null ? 0 : total);
    }
}
