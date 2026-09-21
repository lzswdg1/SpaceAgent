package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.ModelPool;
import com.spaceagent.platform.inference.domain.ModelPoolMember;
import com.spaceagent.platform.inference.domain.ModelPoolRepository;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolStatus;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresModelPoolRepository implements ModelPoolRepository {

    private static final String POOL_COLUMNS = """
            id, tenant_id, owner_id, name, visibility, routing_strategy,
            fallback_enabled, status, created_at, updated_at
            """;
    private static final String MEMBER_COLUMNS = """
            id, pool_id, provider_id, provider_model_id, priority, weight,
            enabled, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresModelPoolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void savePool(ModelPool pool) {
        jdbcTemplate.update("""
                INSERT INTO platform_model_pools (
                    id, tenant_id, owner_id, name, visibility, routing_strategy,
                    fallback_enabled, status, created_at, updated_at
                ) VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    visibility = EXCLUDED.visibility,
                    routing_strategy = EXCLUDED.routing_strategy,
                    fallback_enabled = EXCLUDED.fallback_enabled,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                pool.id(), pool.tenantId(), pool.ownerId(), pool.name(),
                pool.visibility().name(), pool.routingStrategy().name(),
                pool.fallbackEnabled(), pool.status().name(),
                Timestamp.from(pool.createdAt()), Timestamp.from(pool.updatedAt()));
    }

    @Override
    public Optional<ModelPool> findPoolById(String poolId) {
        return jdbcTemplate.query(
                "SELECT " + POOL_COLUMNS + " FROM platform_model_pools "
                        + "WHERE id = CAST(? AS UUID)",
                this::mapPool,
                poolId).stream().findFirst();
    }

    @Override
    public List<ModelPool> findPoolsByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT " + POOL_COLUMNS + " FROM platform_model_pools "
                        + "WHERE tenant_id = ? ORDER BY created_at, id",
                this::mapPool,
                tenantId);
    }

    @Override
    public boolean existsByTenantAndName(String tenantId, String name) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_model_pools WHERE tenant_id = ? AND name = ?
                )
                """, Boolean.class, tenantId, name);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void saveMember(ModelPoolMember member) {
        jdbcTemplate.update("""
                INSERT INTO platform_model_pool_members (
                    id, pool_id, provider_id, provider_model_id, priority, weight,
                    enabled, created_at, updated_at
                ) VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    priority = EXCLUDED.priority,
                    weight = EXCLUDED.weight,
                    enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                member.id(), member.poolId(), member.providerId(), member.providerModelId(),
                member.priority(), member.weight(), member.enabled(),
                Timestamp.from(member.createdAt()), Timestamp.from(member.updatedAt()));
    }

    @Override
    public Optional<ModelPoolMember> findMemberById(String memberId) {
        return jdbcTemplate.query(
                "SELECT " + MEMBER_COLUMNS + " FROM platform_model_pool_members "
                        + "WHERE id = CAST(? AS UUID)",
                this::mapMember,
                memberId).stream().findFirst();
    }

    @Override
    public List<ModelPoolMember> findMembersByPoolId(String poolId) {
        return jdbcTemplate.query(
                "SELECT " + MEMBER_COLUMNS + " FROM platform_model_pool_members "
                        + "WHERE pool_id = CAST(? AS UUID) ORDER BY priority, id",
                this::mapMember,
                poolId);
    }

    @Override
    public boolean removeMember(String poolId, String memberId) {
        return jdbcTemplate.update("""
                DELETE FROM platform_model_pool_members
                WHERE pool_id = CAST(? AS UUID) AND id = CAST(? AS UUID)
                """, poolId, memberId) > 0;
    }

    @Override
    public boolean isProviderReferenced(String providerId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_model_pool_members WHERE provider_id = ?
                )
                """, Boolean.class, providerId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean isProviderModelReferenced(String providerModelId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_model_pool_members WHERE provider_model_id = ?
                )
                """, Boolean.class, providerModelId);
        return Boolean.TRUE.equals(exists);
    }

    private ModelPool mapPool(ResultSet rs, int rowNum) throws SQLException {
        return new ModelPool(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("owner_id"),
                rs.getString("name"), ModelPoolVisibility.valueOf(rs.getString("visibility")),
                ModelPoolRoutingStrategy.valueOf(rs.getString("routing_strategy")),
                rs.getBoolean("fallback_enabled"),
                ModelPoolStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ModelPoolMember mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new ModelPoolMember(
                rs.getString("id"), rs.getString("pool_id"), rs.getString("provider_id"),
                rs.getString("provider_model_id"), rs.getInt("priority"),
                rs.getInt("weight"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
