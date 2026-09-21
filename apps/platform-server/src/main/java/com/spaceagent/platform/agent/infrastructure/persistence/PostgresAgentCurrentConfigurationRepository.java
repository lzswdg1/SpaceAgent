package com.spaceagent.platform.agent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentCurrentConfigurationRepository
        implements AgentCurrentConfigurationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String COLUMNS = """
            agent_id, tenant_id, owner_id, agent_revision, config_hash,
            system_prompt, model_pool_id, model_provider_id, model_id, temperature,
            max_context_tokens, max_output_tokens, max_turns, permission_mode,
            memory_enabled, rag_enabled, network_enabled,
            knowledge_base_ids::text AS knowledge_base_ids,
            enabled_tool_ids::text AS enabled_tool_ids,
            skill_ids::text AS skill_ids, updated_by, updated_at,
            knowledge_collection_ids::text AS knowledge_collection_ids
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresAgentCurrentConfigurationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<AgentCurrentConfiguration> find(
            String tenantId, String ownerUserId, String agentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM platform_agent_current_configurations "
                        + "WHERE tenant_id=? AND owner_id=? AND agent_id=?",
                this::map,
                tenantId, ownerUserId, agentId).stream().findFirst();
    }

    @Override
    public List<AgentCurrentConfiguration> findByTenantAndAgentIds(
            String tenantId, List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(agentIds.size(), "?"));
        Map<String, List<AgentCurrentConfiguration.McpBinding>> bindings =
                bindingsByAgentIds(tenantId, agentIds, placeholders);
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(agentIds);
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM platform_agent_current_configurations WHERE tenant_id=?"
                        + " AND agent_id IN (" + placeholders + ")",
                (result, row) -> map(result,
                        bindings.getOrDefault(result.getString("agent_id"), List.of())),
                arguments.toArray());
    }

    @Override
    @Transactional
    public void insert(AgentCurrentConfiguration configuration) {
        int inserted = jdbc.update("""
                INSERT INTO platform_agent_current_configurations(
                    agent_id,tenant_id,owner_id,agent_revision,config_hash,
                    system_prompt,model_pool_id,model_provider_id,model_id,temperature,
                    max_context_tokens,max_output_tokens,max_turns,permission_mode,
                    memory_enabled,rag_enabled,network_enabled,knowledge_base_ids,
                    enabled_tool_ids,skill_ids,updated_by,updated_at,knowledge_collection_ids)
                VALUES(?,?,?,?,?,?,CAST(? AS UUID),?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),
                       CAST(? AS JSONB),CAST(? AS JSONB),?,?,CAST(? AS JSONB))
                ON CONFLICT (agent_id) DO NOTHING
                """, parameters(configuration));
        if (inserted != 1) {
            throw new IllegalStateException("Agent current configuration already exists");
        }
        replaceBindings(configuration);
    }

    @Override
    @Transactional
    public Optional<AgentCurrentConfiguration> update(
            AgentCurrentConfiguration configuration, long expectedAgentRevision) {
        if (configuration.agentRevision() != expectedAgentRevision + 1) {
            return Optional.empty();
        }
        int updated = jdbc.update("""
                UPDATE platform_agent_current_configurations
                   SET agent_revision=?,config_hash=?,system_prompt=?,model_pool_id=CAST(? AS UUID),
                       model_provider_id=?,model_id=?,temperature=?,max_context_tokens=?,
                       max_output_tokens=?,max_turns=?,permission_mode=?,memory_enabled=?,
                       rag_enabled=?,network_enabled=?,knowledge_base_ids=CAST(? AS JSONB),
                       enabled_tool_ids=CAST(? AS JSONB),skill_ids=CAST(? AS JSONB),
                       updated_by=?,updated_at=?,knowledge_collection_ids=CAST(? AS JSONB)
                 WHERE tenant_id=? AND owner_id=? AND agent_id=? AND agent_revision=?
                """,
                configuration.agentRevision(), configuration.configHash(),
                configuration.systemPrompt(), configuration.modelPoolId(),
                configuration.modelProviderId(), configuration.modelId(),
                configuration.temperature(), configuration.maxContextTokens(),
                configuration.maxOutputTokens(), configuration.maxTurns(),
                configuration.permissionMode(), configuration.memoryEnabled(),
                configuration.ragEnabled(), configuration.networkEnabled(),
                json(configuration.knowledgeBaseIds()), json(configuration.enabledToolIds()),
                json(configuration.skillIds()), configuration.updatedBy(),
                Timestamp.from(configuration.updatedAt()), json(configuration.knowledgeCollectionIds()), configuration.tenantId(),
                configuration.ownerUserId(), configuration.agentId(), expectedAgentRevision);
        if (updated != 1) return Optional.empty();
        replaceBindings(configuration);
        return find(configuration.tenantId(), configuration.ownerUserId(), configuration.agentId());
    }

    private AgentCurrentConfiguration map(ResultSet rs, int rowNumber) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        String ownerId = rs.getString("owner_id");
        String agentId = rs.getString("agent_id");
        return map(rs, bindings(tenantId, ownerId, agentId));
    }

    private AgentCurrentConfiguration map(
            ResultSet rs, List<AgentCurrentConfiguration.McpBinding> bindings) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        String ownerId = rs.getString("owner_id");
        String agentId = rs.getString("agent_id");
        return new AgentCurrentConfiguration(
                agentId, ownerId, tenantId, rs.getLong("agent_revision"),
                rs.getString("config_hash").trim(), rs.getString("model_pool_id"),
                rs.getString("model_provider_id"), rs.getString("model_id"),
                rs.getString("system_prompt"), rs.getDouble("temperature"),
                rs.getInt("max_context_tokens"), rs.getInt("max_output_tokens"),
                rs.getInt("max_turns"), rs.getBoolean("memory_enabled"),
                rs.getBoolean("rag_enabled"), rs.getBoolean("network_enabled"),
                parse(rs.getString("knowledge_base_ids")),
                parse(rs.getString("enabled_tool_ids")), parse(rs.getString("skill_ids")),
                rs.getString("permission_mode"), bindings,
                rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant(), parse(rs.getString("knowledge_collection_ids")));
    }

    private Map<String, List<AgentCurrentConfiguration.McpBinding>> bindingsByAgentIds(
            String tenantId, List<String> agentIds, String placeholders) {
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(agentIds);
        return jdbc.query("""
                SELECT agent_id,id,installation_id,connection_id,server_version_id,
                       capability_snapshot_id,connection_revision,snapshot_sha256,
                       allowed_tool_names::text AS allowed_tool_names,binding_sha256
                  FROM platform_agent_mcp_bindings
                 WHERE tenant_id=? AND agent_id IN (%s)
                 ORDER BY agent_id,id
                """.formatted(placeholders), (rs, row) -> new BindingRow(
                        rs.getString("agent_id"), new AgentCurrentConfiguration.McpBinding(
                        rs.getString("id"), rs.getString("installation_id"),
                        rs.getString("connection_id"), rs.getString("server_version_id"),
                        rs.getString("capability_snapshot_id"), rs.getLong("connection_revision"),
                        rs.getString("snapshot_sha256"), parse(rs.getString("allowed_tool_names")),
                        rs.getString("binding_sha256"))), arguments.toArray()).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        BindingRow::agentId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(BindingRow::binding,
                                java.util.stream.Collectors.toList())));
    }

    private List<AgentCurrentConfiguration.McpBinding> bindings(
            String tenantId, String ownerId, String agentId) {
        return jdbc.query("""
                SELECT id,installation_id,connection_id,server_version_id,
                       capability_snapshot_id,connection_revision,snapshot_sha256,
                       allowed_tool_names::text AS allowed_tool_names,binding_sha256
                  FROM platform_agent_mcp_bindings
                 WHERE tenant_id=? AND owner_id=? AND agent_id=?
                 ORDER BY id
                """, (rs, row) -> new AgentCurrentConfiguration.McpBinding(
                rs.getString("id"), rs.getString("installation_id"),
                rs.getString("connection_id"), rs.getString("server_version_id"),
                rs.getString("capability_snapshot_id"), rs.getLong("connection_revision"),
                rs.getString("snapshot_sha256"), parse(rs.getString("allowed_tool_names")),
                rs.getString("binding_sha256")), tenantId, ownerId, agentId);
    }

    private void replaceBindings(AgentCurrentConfiguration configuration) {
        jdbc.update("DELETE FROM platform_agent_mcp_bindings WHERE agent_id=?", configuration.agentId());
        for (AgentCurrentConfiguration.McpBinding binding : configuration.mcpBindings()) {
            jdbc.update("""
                    INSERT INTO platform_agent_mcp_bindings(
                        id,tenant_id,owner_id,agent_id,installation_id,connection_id,
                        server_version_id,capability_snapshot_id,connection_revision,
                        snapshot_sha256,allowed_tool_names,binding_sha256,created_by,created_at)
                    VALUES(CAST(? AS UUID),?,?,?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                           CAST(? AS UUID),?,?,CAST(? AS JSONB),?,?,?)
                    """, binding.id(), configuration.tenantId(), configuration.ownerUserId(),
                    configuration.agentId(), binding.installationId(), binding.connectionId(),
                    binding.serverVersionId(), binding.capabilitySnapshotId(),
                    binding.connectionRevision(), binding.snapshotSha256(),
                    json(binding.allowedToolNames()), binding.bindingSha256(),
                    configuration.updatedBy(), Timestamp.from(configuration.updatedAt()));
        }
    }

    private Object[] parameters(AgentCurrentConfiguration value) {
        return new Object[] {
                value.agentId(), value.tenantId(), value.ownerUserId(), value.agentRevision(),
                value.configHash(), value.systemPrompt(), value.modelPoolId(),
                value.modelProviderId(), value.modelId(), value.temperature(),
                value.maxContextTokens(), value.maxOutputTokens(), value.maxTurns(),
                value.permissionMode(), value.memoryEnabled(), value.ragEnabled(),
                value.networkEnabled(), json(value.knowledgeBaseIds()),
                json(value.enabledToolIds()), json(value.skillIds()), value.updatedBy(),
                Timestamp.from(value.updatedAt()), json(value.knowledgeCollectionIds())
        };
    }

    private String json(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize Agent current configuration", error);
        }
    }

    private List<String> parse(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to parse Agent current configuration", error);
        }
    }

    private record BindingRow(String agentId, AgentCurrentConfiguration.McpBinding binding) { }
}
