package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentRunConfigurationSnapshotRepository
        implements AgentRunConfigurationSnapshotRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String COLUMNS = """
            id,run_id,tenant_id,owner_id,agent_id,snapshot_state,agent_revision,config_hash,
            system_prompt,model_pool_id,model_provider_id,model_id,temperature,
            max_context_tokens,max_output_tokens,max_turns,permission_mode,
            memory_enabled,rag_enabled,network_enabled,
            knowledge_base_ids::text AS knowledge_base_ids,
            enabled_tool_ids::text AS enabled_tool_ids,
            skill_ids::text AS skill_ids,source_updated_by,source_updated_at,captured_at,
            knowledge_collection_ids::text AS knowledge_collection_ids
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresAgentRunConfigurationSnapshotRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public AgentRunConfigurationSnapshot insertIfAbsent(AgentRunConfigurationSnapshot snapshot) {
        int inserted = jdbc.update("""
                INSERT INTO platform_agent_run_configuration_snapshots(
                    id,run_id,tenant_id,owner_id,agent_id,snapshot_state,agent_revision,config_hash,
                    system_prompt,model_pool_id,model_provider_id,model_id,temperature,
                    max_context_tokens,max_output_tokens,max_turns,permission_mode,
                    memory_enabled,rag_enabled,network_enabled,knowledge_base_ids,enabled_tool_ids,
                    skill_ids,source_updated_by,source_updated_at,captured_at,knowledge_collection_ids)
                VALUES(?,?,?,?,?,?,?, ?,?,CAST(? AS UUID),?,?,?, ?,?,?,?, ?,?,?,
                       CAST(? AS JSONB),CAST(? AS JSONB),CAST(? AS JSONB),?,?,?,CAST(? AS JSONB))
                ON CONFLICT (run_id) DO NOTHING
                """, snapshot.id(), snapshot.runId(), snapshot.tenantId(), snapshot.ownerUserId(),
                snapshot.agentId(), snapshot.state().name(), snapshot.agentRevision(),
                snapshot.configHash(), snapshot.systemPrompt(), snapshot.modelPoolId(),
                snapshot.modelProviderId(), snapshot.modelId(), snapshot.temperature(),
                snapshot.maxContextTokens(), snapshot.maxOutputTokens(), snapshot.maxTurns(),
                snapshot.permissionMode(), snapshot.memoryEnabled(), snapshot.ragEnabled(),
                snapshot.networkEnabled(), nullableJson(snapshot.knowledgeBaseIds()),
                nullableJson(snapshot.enabledToolIds()), nullableJson(snapshot.skillIds()),
                snapshot.sourceUpdatedBy(), timestamp(snapshot.sourceUpdatedAt()),
                Timestamp.from(snapshot.capturedAt()), nullableJson(snapshot.knowledgeCollectionIds()));
        AgentRunConfigurationSnapshot stored = findByRunId(
                snapshot.tenantId(), snapshot.ownerUserId(), snapshot.runId())
                .orElseThrow(() -> new IllegalStateException("Run Agent configuration snapshot insert failed"));
        if (inserted == 0 && !stored.equals(snapshot)) {
            throw new IllegalStateException("Run Agent configuration snapshot conflict");
        }
        if (inserted == 1) insertBindings(snapshot);
        return inserted == 1 ? findByRunId(
                snapshot.tenantId(), snapshot.ownerUserId(), snapshot.runId()).orElseThrow() : stored;
    }

    @Override
    public Optional<AgentRunConfigurationSnapshot> findByRunId(
            String tenantId, String ownerUserId, String runId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM platform_agent_run_configuration_snapshots "
                        + "WHERE tenant_id=? AND owner_id=? AND run_id=?",
                this::map, tenantId, ownerUserId, runId).stream().findFirst();
    }

    private AgentRunConfigurationSnapshot map(ResultSet rs, int rowNumber) throws SQLException {
        String id = rs.getString("id");
        var state = AgentRunConfigurationSnapshot.State.valueOf(rs.getString("snapshot_state"));
        return new AgentRunConfigurationSnapshot(
                id, rs.getString("run_id"), rs.getString("tenant_id"), rs.getString("owner_id"),
                rs.getString("agent_id"), state, number(rs, "agent_revision"),
                trimmed(rs.getString("config_hash")), rs.getString("model_pool_id"),
                rs.getString("model_provider_id"), rs.getString("model_id"),
                rs.getString("system_prompt"), decimal(rs, "temperature"),
                integer(rs, "max_context_tokens"), integer(rs, "max_output_tokens"),
                integer(rs, "max_turns"), bool(rs, "memory_enabled"), bool(rs, "rag_enabled"),
                bool(rs, "network_enabled"), parseNullable(rs.getString("knowledge_base_ids")),
                parseNullable(rs.getString("enabled_tool_ids")),
                parseNullable(rs.getString("skill_ids")), rs.getString("permission_mode"),
                bindings(id), rs.getString("source_updated_by"),
                instant(rs.getTimestamp("source_updated_at")), rs.getTimestamp("captured_at").toInstant(),
                parseNullable(rs.getString("knowledge_collection_ids")));
    }

    private List<AgentRunConfigurationSnapshot.McpBinding> bindings(String snapshotId) {
        return jdbc.query("""
                SELECT source_binding_id,installation_id,connection_id,server_version_id,
                       capability_snapshot_id,connection_revision,snapshot_sha256,
                       allowed_tool_names::text AS allowed_tool_names,binding_sha256
                  FROM platform_agent_run_mcp_binding_snapshots
                 WHERE run_configuration_snapshot_id=? ORDER BY source_binding_id
                """, (rs, row) -> new AgentRunConfigurationSnapshot.McpBinding(
                rs.getString("source_binding_id"), rs.getString("installation_id"),
                rs.getString("connection_id"), rs.getString("server_version_id"),
                rs.getString("capability_snapshot_id"), rs.getLong("connection_revision"),
                rs.getString("snapshot_sha256"), parse(rs.getString("allowed_tool_names")),
                rs.getString("binding_sha256")), snapshotId);
    }

    private void insertBindings(AgentRunConfigurationSnapshot snapshot) {
        for (AgentRunConfigurationSnapshot.McpBinding binding : snapshot.mcpBindings()) {
            jdbc.update("""
                    INSERT INTO platform_agent_run_mcp_binding_snapshots(
                        run_configuration_snapshot_id,source_binding_id,installation_id,
                        connection_id,server_version_id,capability_snapshot_id,
                        connection_revision,snapshot_sha256,allowed_tool_names,binding_sha256)
                    VALUES(?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                           CAST(? AS UUID),?,?,CAST(? AS JSONB),?)
                    """, snapshot.id(), binding.sourceBindingId(), binding.installationId(),
                    binding.connectionId(), binding.serverVersionId(),
                    binding.capabilitySnapshotId(), binding.connectionRevision(),
                    binding.snapshotSha256(), json(binding.allowedToolNames()),
                    binding.bindingSha256());
        }
    }

    private String nullableJson(List<String> values) {
        return values == null ? null : json(values);
    }

    private String json(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize Run Agent configuration snapshot", error);
        }
    }

    private List<String> parse(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to parse Run Agent configuration snapshot", error);
        }
    }

    private List<String> parseNullable(String value) {
        return value == null ? null : parse(value);
    }

    private static Long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
