package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.api.InferenceCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresInferenceCleanupService implements InferenceCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresInferenceCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        rejectUnresolved("tenant_id",organizationId);
        jdbc.update("DELETE FROM platform_inference_budget_reservations WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_embedding_calls WHERE tenant_id = ?",organizationId);
        jdbc.update("DELETE FROM platform_inference_budget_periods WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_inference_budget_policies WHERE tenant_id = ?", organizationId);
        jdbc.update("""
                DELETE FROM platform_model_pool_members member
                USING platform_model_pools pool
                WHERE member.pool_id = pool.id AND pool.tenant_id = ?
                """, organizationId);
        jdbc.update("DELETE FROM platform_model_pools WHERE tenant_id = ?", organizationId);
        jdbc.update("""
                DELETE FROM platform_provider_models model
                USING platform_model_providers provider
                WHERE model.provider_id = provider.id AND provider.tenant_id = ?
                """, organizationId);
        jdbc.update("DELETE FROM platform_model_providers WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupUser(String userId) {
        Long count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM platform_model_providers WHERE owner_id = ?)
                     + (SELECT count(*) FROM platform_model_pools WHERE owner_id = ?)
                """, Long.class, userId, userId);
        if (count != null && count > 0L) throw new IllegalStateException("USER_OWNS_INFERENCE_RESOURCES");
        rejectUnresolved("actor_id",userId);
        jdbc.update("DELETE FROM platform_inference_budget_reservations WHERE embedding_call_id IN (SELECT id FROM platform_embedding_calls WHERE actor_id=?)",userId);
        jdbc.update("DELETE FROM platform_embedding_calls WHERE actor_id=?",userId);
    }
    private void rejectUnresolved(String column,String id) {
        // column comes only from the two literals above, never user input.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_embedding_calls WHERE "+column
                +"=? AND (state IN ('PREPARED','DISPATCHED','UNKNOWN') OR (state='SUCCEEDED' AND input_tokens IS NULL)))",Boolean.class,id)))
            throw new IllegalStateException("EMBEDDING_RECONCILIATION_REQUIRED");
    }
}
