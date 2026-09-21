package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.InferenceSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresInferenceSystemAdministrationQuery implements InferenceSystemAdministrationQuery {
    private final JdbcTemplate jdbc;

    public PostgresInferenceSystemAdministrationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OverviewRow overview() {
        return jdbc.queryForObject("""
                SELECT
                  count(*) providers,
                  count(*) FILTER (WHERE connection_status = 'ACTIVE') active_providers,
                  count(*) FILTER (WHERE connection_status = 'UNHEALTHY') unhealthy_providers,
                  count(*) FILTER (WHERE connection_status = 'UNTESTED') untested_providers,
                  count(*) FILTER (WHERE connection_status = 'DISABLED') disabled_providers,
                  (SELECT count(*) FROM platform_model_pools) model_pools,
                  (SELECT count(*) FROM platform_model_pools WHERE status = 'ACTIVE') active_model_pools,
                  (SELECT count(*) FROM platform_model_call_ledger WHERE status = 'UNKNOWN') unknown_model_calls
                FROM platform_model_providers
                """, (rs, row) -> new OverviewRow(rs.getLong("providers"),
                rs.getLong("active_providers"), rs.getLong("unhealthy_providers"),
                rs.getLong("untested_providers"), rs.getLong("disabled_providers"),
                rs.getLong("model_pools"), rs.getLong("active_model_pools"), rs.getLong("unknown_model_calls")));
    }

    @Override
    public PageRows<ProviderRow> providerCredentials(int offset, int limit) {
        return providerCredentials(null, offset, limit);
    }

    @Override
    public PageRows<ProviderRow> providerCredentialsByOwner(String userId, int offset, int limit) {
        return providerCredentials(userId, offset, limit);
    }

    private PageRows<ProviderRow> providerCredentials(String userId, int offset, int limit) {
        String ownerFilter = userId == null ? "" : "WHERE provider.owner_id = ?";
        String sql = """
                SELECT provider.id, provider.tenant_id, provider.owner_id, provider.name,
                       provider.provider_type, provider.base_url, provider.auth_type,
                       provider.api_key_ciphertext IS NOT NULL
                         AND provider.api_key_ciphertext <> '' AS secret_configured,
                       provider.secret_hint, provider.secret_fingerprint, provider.secret_key_version,
                       provider.enabled, provider.is_default, provider.connection_status,
                       provider.last_tested_at, provider.last_test_latency_ms,
                       provider.last_test_error_code,
                       (SELECT count(*) FROM platform_provider_models model
                         WHERE model.provider_id = provider.id) model_count,
                       (SELECT count(*) FROM platform_model_pool_members member
                         WHERE member.provider_id = provider.id) pool_usage_count
                FROM platform_model_providers provider
                %s
                ORDER BY provider.created_at DESC, provider.id
                LIMIT ? OFFSET ?
                """.formatted(ownerFilter);
        var items = userId == null
                ? jdbc.query(sql, this::map, limit, offset)
                : jdbc.query(sql, this::map, userId, limit, offset);
        Long total = userId == null
                ? jdbc.queryForObject("SELECT count(*) FROM platform_model_providers", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM platform_model_providers WHERE owner_id = ?",
                        Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public PageRows<ResourceRow> modelPoolsByOwner(String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT pool.id::text id, pool.tenant_id, NULL::text parent_id, pool.name,
                       pool.status, pool.visibility || ':' || pool.routing_strategy relation,
                       pool.created_at, pool.updated_at, NULL::text safe_error_code,
                       (SELECT count(*) FROM platform_model_pool_members member
                         WHERE member.pool_id = pool.id) primary_count,
                       (SELECT count(*) FROM platform_model_pool_members member
                         WHERE member.pool_id = pool.id AND member.enabled) secondary_count
                FROM platform_model_pools pool WHERE pool.owner_id = ?
                ORDER BY pool.updated_at DESC, pool.id LIMIT ? OFFSET ?
                """, this::mapResource, userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_model_pools WHERE owner_id = ?", Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public PageRows<ResourceRow> modelEffectsByOwner(String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT call.id, run.tenant_id, call.agent_run_id parent_id, NULL::text name,
                       call.status, call.provider_id || ':' || call.model_id relation,
                       call.created_at, call.updated_at, call.error_code safe_error_code,
                       0::bigint primary_count, 0::bigint secondary_count
                FROM platform_model_call_ledger call
                JOIN platform_agent_runs run ON run.id = call.agent_run_id
                WHERE run.owner_id = ? AND call.status IN ('FAILED','TIMED_OUT','UNKNOWN')
                ORDER BY call.updated_at DESC, call.id LIMIT ? OFFSET ?
                """, this::mapResource, userId, limit, offset);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM platform_model_call_ledger call
                JOIN platform_agent_runs run ON run.id = call.agent_run_id
                WHERE run.owner_id = ? AND call.status IN ('FAILED','TIMED_OUT','UNKNOWN')
                """, Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public DeletionEvidenceRow deletionEvidence(String userId) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM platform_model_providers WHERE owner_id = ?) owned_providers,
                  (SELECT count(*) FROM platform_model_pools WHERE owner_id = ?) owned_model_pools,
                  (SELECT count(*) FROM platform_model_call_ledger call
                    JOIN platform_agent_runs run ON run.id = call.agent_run_id
                   WHERE run.owner_id = ? AND call.status = 'UNKNOWN') unknown_model_calls
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("owned_providers"),
                rs.getLong("owned_model_pools"), rs.getLong("unknown_model_calls")),
                userId, userId, userId);
    }

    private ProviderRow map(ResultSet rs, int row) throws SQLException {
        Timestamp lastTested = rs.getTimestamp("last_tested_at");
        Integer latency = (Integer) rs.getObject("last_test_latency_ms");
        return new ProviderRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("owner_id"), rs.getString("name"), rs.getString("provider_type"),
                rs.getString("base_url"), rs.getString("auth_type"),
                rs.getBoolean("secret_configured"), rs.getString("secret_hint"),
                rs.getString("secret_fingerprint"), rs.getString("secret_key_version"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"),
                rs.getString("connection_status"), lastTested == null ? null : lastTested.toInstant(),
                latency, rs.getString("last_test_error_code"), rs.getLong("model_count"),
                rs.getLong("pool_usage_count"));
    }

    private ResourceRow mapResource(ResultSet rs, int row) throws SQLException {
        return new ResourceRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("parent_id"), rs.getString("name"), rs.getString("status"),
                rs.getString("relation"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("primary_count"), rs.getLong("secondary_count"));
    }
}
