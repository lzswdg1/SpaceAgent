package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ProviderModel;
import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL persistence for inference providers and models.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresInferenceProviderRepository implements InferenceProviderRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresInferenceProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ModelProvider> findProviderById(String id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, owner_id, name, provider_type, base_url,
                       api_key_ciphertext, auth_type, enabled, is_default,
                       connection_status, last_tested_at, last_test_latency_ms,
                       last_test_error_code, created_at, updated_at
                FROM platform_model_providers
                WHERE id = ?
                """, this::mapProvider, id).stream().findFirst();
    }

    @Override
    public Optional<ModelProvider> findProviderByTenantAndId(String tenantId, String id) {
        return findProviderById(id).filter(provider -> tenantId.equals(provider.tenantId()));
    }

    @Override
    public List<ModelProvider> findProvidersByTenantId(String tenantId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, owner_id, name, provider_type, base_url,
                       api_key_ciphertext, auth_type, enabled, is_default,
                       connection_status, last_tested_at, last_test_latency_ms,
                       last_test_error_code, created_at, updated_at
                FROM platform_model_providers
                WHERE tenant_id = ?
                ORDER BY created_at, id
                """, this::mapProvider, tenantId);
    }

    @Override
    public List<ModelProvider> findProvidersByTenantAndIds(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(ids);
        return jdbcTemplate.query("""
                SELECT id, tenant_id, owner_id, name, provider_type, base_url,
                       api_key_ciphertext, auth_type, enabled, is_default,
                       connection_status, last_tested_at, last_test_latency_ms,
                       last_test_error_code, created_at, updated_at
                FROM platform_model_providers
                WHERE tenant_id = ? AND id IN (%s)
                """.formatted(placeholders), this::mapProvider, arguments.toArray());
    }

    @Override
    public void saveProvider(ModelProvider provider) {
        jdbcTemplate.update("""
                INSERT INTO platform_model_providers (
                    id, tenant_id, owner_id, name, provider_type, base_url,
                    api_key_ciphertext, auth_type, enabled, is_default,
                    connection_status, last_tested_at, last_test_latency_ms,
                    last_test_error_code, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id,
                    owner_id = EXCLUDED.owner_id,
                    name = EXCLUDED.name,
                    provider_type = EXCLUDED.provider_type,
                    base_url = EXCLUDED.base_url,
                    api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                    auth_type = EXCLUDED.auth_type,
                    enabled = EXCLUDED.enabled,
                    is_default = EXCLUDED.is_default,
                    connection_status = EXCLUDED.connection_status,
                    last_tested_at = EXCLUDED.last_tested_at,
                    last_test_latency_ms = EXCLUDED.last_test_latency_ms,
                    last_test_error_code = EXCLUDED.last_test_error_code,
                    updated_at = EXCLUDED.updated_at
                """,
                provider.id(), provider.tenantId(), provider.ownerId(), provider.name(),
                provider.providerType(), provider.baseUrl(), provider.encryptedApiKey(),
                provider.authType(), provider.enabled(), provider.isDefault(),
                provider.connectionStatus().name(), timestamp(provider.lastTestedAt()),
                provider.lastTestLatencyMs(), provider.lastTestErrorCode(),
                Timestamp.from(provider.createdAt()), Timestamp.from(provider.updatedAt()));
    }

    @Override
    public void deleteProvider(String tenantId, String id) {
        jdbcTemplate.update("DELETE FROM platform_model_providers WHERE id = ? AND tenant_id = ?", id, tenantId);
    }

    @Override
    public Optional<ProviderModel> findModelById(String modelId) {
        return jdbcTemplate.query("""
                SELECT id, provider_id, model_id, display_name,
                       max_context_tokens, is_default, created_at
                FROM platform_provider_models
                WHERE id = ?
                """, this::mapModel, modelId).stream().findFirst();
    }

    @Override
    public List<ProviderModel> findModelsByIds(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(modelIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, provider_id, model_id, display_name,
                       max_context_tokens, is_default, created_at
                FROM platform_provider_models
                WHERE id IN (%s)
                """.formatted(placeholders), this::mapModel, modelIds.toArray());
    }

    @Override
    public List<ProviderModel> findModelsByProviderId(String providerId) {
        return jdbcTemplate.query("""
                SELECT id, provider_id, model_id, display_name,
                       max_context_tokens, is_default, created_at
                FROM platform_provider_models
                WHERE provider_id = ?
                ORDER BY is_default DESC, created_at, id
                """, this::mapModel, providerId);
    }

    @Override
    public void saveModel(ProviderModel model) {
        jdbcTemplate.update("""
                INSERT INTO platform_provider_models (
                    id, provider_id, model_id, display_name,
                    max_context_tokens, is_default, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id,
                    model_id = EXCLUDED.model_id,
                    display_name = EXCLUDED.display_name,
                    max_context_tokens = EXCLUDED.max_context_tokens,
                    is_default = EXCLUDED.is_default
                """,
                model.id(), model.providerId(), model.modelId(), model.displayName(),
                model.maxContextTokens(), model.isDefault(), Timestamp.from(model.createdAt()));
    }

    @Override
    public void deleteModel(String providerId, String modelId) {
        jdbcTemplate.update(
                "DELETE FROM platform_provider_models WHERE provider_id = ? AND model_id = ?",
                providerId,
                modelId);
    }

    @Override
    public void deleteModelsByProviderId(String providerId) {
        jdbcTemplate.update("DELETE FROM platform_provider_models WHERE provider_id = ?", providerId);
    }

    private ModelProvider mapProvider(ResultSet rs, int rowNum) throws SQLException {
        return new ModelProvider(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("owner_id"),
                rs.getString("name"),
                rs.getString("provider_type"),
                rs.getString("base_url"),
                rs.getString("api_key_ciphertext"),
                rs.getString("auth_type"),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                ProviderConnectionStatus.valueOf(rs.getString("connection_status")),
                instant(rs.getTimestamp("last_tested_at")),
                (Integer) rs.getObject("last_test_latency_ms"),
                rs.getString("last_test_error_code"));
    }

    private ProviderModel mapModel(ResultSet rs, int rowNum) throws SQLException {
        return new ProviderModel(
                rs.getString("id"),
                rs.getString("provider_id"),
                rs.getString("model_id"),
                rs.getString("display_name"),
                rs.getInt("max_context_tokens"),
                rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
