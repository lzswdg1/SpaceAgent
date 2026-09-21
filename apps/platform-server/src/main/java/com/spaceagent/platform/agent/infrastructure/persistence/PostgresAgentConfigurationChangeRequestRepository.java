package com.spaceagent.platform.agent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequest;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequestRepository;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentConfigurationChangeRequestRepository
        implements AgentConfigurationChangeRequestRepository {

    private static final String SELECT = "SELECT * FROM platform_agent_configuration_change_requests";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresAgentConfigurationChangeRequestRepository(
            JdbcTemplate jdbc,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<AgentConfigurationChangeRequest> findById(String tenantId, String requestId) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND id=CAST(? AS UUID)",
                this::map, tenantId, requestId).stream().findFirst();
    }

    @Override
    public Optional<AgentConfigurationChangeRequest> findByIdForUpdate(
            String tenantId, String requestId) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND id=CAST(? AS UUID) FOR UPDATE",
                this::map, tenantId, requestId).stream().findFirst();
    }

    @Override
    public Optional<AgentConfigurationChangeRequest> findPending(
            String tenantId, String agentId, String requestedBy) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND agent_id=? AND requested_by=? "
                        + "AND state='PENDING' FOR UPDATE",
                this::map, tenantId, agentId, requestedBy).stream().findFirst();
    }

    @Override
    public List<AgentConfigurationChangeRequest> listByAgent(
            String tenantId, String agentId, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND agent_id=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, tenantId, agentId, limit, offset);
    }

    @Override
    public void insert(AgentConfigurationChangeRequest value) {
        jdbc.update("""
                INSERT INTO platform_agent_configuration_change_requests(
                    id,approval_id,tenant_id,agent_id,agent_owner_id,requested_by,
                    base_agent_revision,base_config_hash,proposal_hash,proposal_json,state,
                    closed_by,decision_note,closed_at,applied_agent_revision,revision,
                    created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?,CAST(? AS JSONB),?,
                       ?,?,?,?, ?,?,?)
                """,
                value.id(), value.approvalId(), value.tenantId(), value.agentId(),
                value.agentOwnerId(), value.requestedBy(), value.baseAgentRevision(),
                value.baseConfigHash(), value.proposalHash(), proposal(value.proposal()),
                value.state().name(), value.closedBy(), value.decisionNote(), timestamp(value.closedAt()),
                value.appliedAgentRevision(), value.revision(), Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
    }

    @Override
    public Optional<AgentConfigurationChangeRequest> update(
            AgentConfigurationChangeRequest value,
            long expectedRevision,
            AgentConfigurationChangeState expectedState) {
        int updated = jdbc.update("""
                UPDATE platform_agent_configuration_change_requests SET
                    approval_id=CAST(? AS UUID),base_agent_revision=?,base_config_hash=?,
                    proposal_hash=?,proposal_json=CAST(? AS JSONB),state=?,closed_by=?,
                    decision_note=?,closed_at=?,applied_agent_revision=?,revision=?,updated_at=?
                WHERE tenant_id=? AND id=CAST(? AS UUID) AND revision=? AND state=?
                """,
                value.approvalId(), value.baseAgentRevision(), value.baseConfigHash(),
                value.proposalHash(), proposal(value.proposal()), value.state().name(),
                value.closedBy(), value.decisionNote(), timestamp(value.closedAt()),
                value.appliedAgentRevision(), value.revision(), Timestamp.from(value.updatedAt()),
                value.tenantId(), value.id(), expectedRevision, expectedState.name());
        return updated == 1 ? Optional.of(value) : Optional.empty();
    }

    private AgentConfigurationChangeRequest map(ResultSet rs, int row) throws SQLException {
        return new AgentConfigurationChangeRequest(
                rs.getString("id"), rs.getString("approval_id"), rs.getString("tenant_id"),
                rs.getString("agent_id"), rs.getString("agent_owner_id"),
                rs.getString("requested_by"), rs.getLong("base_agent_revision"),
                rs.getString("base_config_hash"), rs.getString("proposal_hash"),
                readProposal(rs.getString("proposal_json")),
                AgentConfigurationChangeState.valueOf(rs.getString("state")),
                rs.getString("closed_by"), rs.getString("decision_note"),
                instant(rs.getTimestamp("closed_at")), nullableLong(rs, "applied_agent_revision"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String proposal(AgentConfigurationProposal value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent configuration proposal could not be serialized", exception);
        }
    }

    private AgentConfigurationProposal readProposal(String value) throws SQLException {
        if (value == null) return null;
        try {
            return json.readValue(value, AgentConfigurationProposal.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Agent configuration proposal could not be deserialized", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
