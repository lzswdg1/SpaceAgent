package com.spaceagent.platform.agent.infrastructure.persistence;

import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.domain.AgentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Authoritative PostgreSQL adapter for the canonical AgentDefinition aggregate. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentRepository implements AgentRepository {

    private static final String COLUMNS = """
            id, owner_id, tenant_id, name, description, status,
            revision, created_at, updated_at, archived_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresAgentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AgentDefinition> findById(String id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE id = ? AND archived_at IS NULL",
                this::map,
                id).stream().findFirst();
    }

    @Override
    public Optional<AgentDefinition> findByIdForUpdate(String id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                this::map,
                id).stream().findFirst();
    }

    @Override
    public List<AgentDefinition> findByOwnerId(String ownerId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE owner_id = ? AND archived_at IS NULL "
                        + "ORDER BY updated_at DESC, created_at DESC",
                this::map,
                ownerId);
    }

    @Override
    public List<AgentDefinition> findByTenantAndOwnerId(String tenantId, String ownerId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE tenant_id = ? AND owner_id = ? AND archived_at IS NULL "
                        + "ORDER BY updated_at DESC, created_at DESC",
                this::map,
                tenantId,
                ownerId);
    }

    @Override
    public List<AgentDefinition> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE tenant_id = ? AND archived_at IS NULL "
                        + "ORDER BY updated_at DESC, created_at DESC, id",
                this::map,
                tenantId);
    }

    @Override
    public Optional<AgentDefinition> findByTenantAndName(String tenantId, String name) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_agent_definitions "
                        + "WHERE tenant_id = ? AND name = ? AND archived_at IS NULL",
                this::map,
                tenantId,
                name).stream().findFirst();
    }

    @Override
    public List<AgentDefinition> findPage(String tenantId, String ownerId, int offset, int limit) {
        String owned = ownerId == null ? "" : " AND owner_id=?";
        Object[] args = ownerId == null ? new Object[]{tenantId, limit, offset} : new Object[]{tenantId, ownerId, limit, offset};
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_agent_definitions WHERE tenant_id=?" + owned
                + " AND archived_at IS NULL ORDER BY updated_at DESC,id LIMIT ? OFFSET ?", this::map, args);
    }

    @Override
    public void save(AgentDefinition definition) {
        jdbcTemplate.update("""
                INSERT INTO platform_agent_definitions (
                    id, owner_id, tenant_id, name, description, status,
                    revision, created_at, updated_at, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    tenant_id = EXCLUDED.tenant_id,
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    revision = EXCLUDED.revision,
                    updated_at = EXCLUDED.updated_at,
                    archived_at = EXCLUDED.archived_at
                """,
                definition.id(), definition.ownerId(), definition.tenantId(), definition.name(),
                definition.description(), definition.status().name(), definition.revision(),
                Timestamp.from(definition.createdAt()),
                Timestamp.from(definition.updatedAt()), timestamp(definition.archivedAt()));
    }

    @Override
    @Transactional
    public void replaceKnowledgeBindings(String agentId, List<String> knowledgeBaseIds) {
        jdbcTemplate.update("DELETE FROM platform_agent_knowledge_bindings WHERE agent_id = ?", agentId);
        knowledgeBaseIds.stream().distinct().forEach(knowledgeId -> jdbcTemplate.update("""
                INSERT INTO platform_agent_knowledge_bindings (agent_id, knowledge_base_id)
                VALUES (?, ?)
                ON CONFLICT (agent_id, knowledge_base_id) DO NOTHING
                """, agentId, knowledgeId));
    }

    private AgentDefinition map(ResultSet rs, int rowNum) throws SQLException {
        return new AgentDefinition(
                rs.getString("id"),
                rs.getString("owner_id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                AgentDefinitionStatus.valueOf(rs.getString("status")),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("archived_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
