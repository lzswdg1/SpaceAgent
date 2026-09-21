package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserIdentity;
import com.spaceagent.platform.identity.domain.UserProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL identity repository for the platform-server module.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityRepository implements IdentityRepository {

    private static final String TENANT_COLUMNS = """
            id, name, slug, status, creator_user_id, created_at, updated_at,
            deletion_requested_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserIdentity> findUserById(String id) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, external_id, display_name, created_at, updated_at "
                        + "FROM platform_users WHERE id = ?",
                this::mapUser,
                id).stream().findFirst();
    }

    @Override
    public Optional<UserIdentity> findUserByExternalId(String tenantId, String externalId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, external_id, display_name, created_at, updated_at "
                        + "FROM platform_users WHERE tenant_id = ? AND external_id = ?",
                this::mapUser,
                tenantId,
                externalId).stream().findFirst();
    }

    @Override
    public List<UserIdentity> findUsersByExternalIdIgnoreCase(String externalId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, external_id, display_name, created_at, updated_at "
                        + "FROM platform_users WHERE lower(external_id) = lower(?) "
                        + "ORDER BY created_at, id",
                this::mapUser,
                externalId);
    }

    @Override
    public List<UserIdentity> findUsersByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, external_id, display_name, created_at, updated_at "
                        + "FROM platform_users WHERE tenant_id = ? ORDER BY created_at, id",
                this::mapUser,
                tenantId);
    }

    @Override
    public Optional<Tenant> findTenantById(String id) {
        return jdbcTemplate.query(
                "SELECT " + TENANT_COLUMNS + " FROM platform_tenants WHERE id = ?",
                this::mapTenant,
                id).stream().findFirst();
    }

    @Override
    public List<Tenant> findTenantsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + TENANT_COLUMNS + " FROM platform_tenants WHERE id IN ("
                        + placeholders + ")",
                this::mapTenant,
                ids.toArray());
    }

    @Override
    public Optional<Tenant> findTenantByIdForUpdate(String id) {
        return jdbcTemplate.query(
                "SELECT " + TENANT_COLUMNS + " FROM platform_tenants WHERE id = ? FOR UPDATE",
                this::mapTenant,
                id).stream().findFirst();
    }

    @Override
    public Optional<Tenant> findTenantBySlug(String slug) {
        return jdbcTemplate.query(
                "SELECT " + TENANT_COLUMNS + " FROM platform_tenants WHERE slug = ?",
                this::mapTenant,
                slug).stream().findFirst();
    }

    @Override
    public Optional<TenantMembership> findMembership(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, user_id, tenant_role, status, joined_at, updated_at
                FROM platform_tenant_memberships
                WHERE tenant_id = ? AND user_id = ?
                """, this::mapMembership, tenantId, userId).stream().findFirst();
    }

    @Override
    public Optional<TenantMembership> findMembershipForUpdate(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, user_id, tenant_role, status, joined_at, updated_at
                FROM platform_tenant_memberships
                WHERE tenant_id = ? AND user_id = ?
                FOR UPDATE
                """, this::mapMembership, tenantId, userId).stream().findFirst();
    }

    @Override
    public List<TenantMembership> findMembershipsByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, user_id, tenant_role, status, joined_at, updated_at
                FROM platform_tenant_memberships
                WHERE user_id = ?
                ORDER BY joined_at, tenant_id
                """, this::mapMembership, userId);
    }

    @Override
    public List<TenantMembership> findMembershipsByTenantId(String tenantId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, user_id, tenant_role, status, joined_at, updated_at
                FROM platform_tenant_memberships
                WHERE tenant_id = ?
                ORDER BY joined_at, user_id
                """, this::mapMembership, tenantId);
    }

    @Override
    public long countActiveMemberships(String tenantId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM platform_tenant_memberships
                WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    @Override
    public void saveUser(UserIdentity user) {
        jdbcTemplate.update("""
                INSERT INTO platform_users (id, tenant_id, external_id, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id,
                    external_id = EXCLUDED.external_id,
                    display_name = EXCLUDED.display_name,
                    updated_at = EXCLUDED.updated_at
                """,
                user.id(), user.tenantId(), user.externalId(), user.displayName(),
                Timestamp.from(user.createdAt()), Timestamp.from(user.updatedAt()));
    }

    @Override
    public void saveTenant(Tenant tenant) {
        jdbcTemplate.update("""
                INSERT INTO platform_tenants (
                    id, name, slug, status, creator_user_id,
                    created_at, updated_at, deletion_requested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    slug = EXCLUDED.slug,
                    status = EXCLUDED.status,
                    creator_user_id = EXCLUDED.creator_user_id,
                    updated_at = EXCLUDED.updated_at,
                    deletion_requested_at = EXCLUDED.deletion_requested_at
                """,
                tenant.id(), tenant.name(), tenant.slug(), tenant.status().name(),
                tenant.creatorUserId(), Timestamp.from(tenant.createdAt()),
                Timestamp.from(tenant.updatedAt()), timestamp(tenant.deletionRequestedAt()));
    }

    @Override
    public void saveMembership(TenantMembership membership) {
        jdbcTemplate.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET
                    tenant_role = EXCLUDED.tenant_role,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                membership.tenantId(),
                membership.userId(),
                membership.role().name(),
                membership.status().name(),
                Timestamp.from(membership.joinedAt()),
                Timestamp.from(membership.updatedAt()));
    }

    @Override
    public void updateUserDisplayName(String userId, String displayName) {
        jdbcTemplate.update("UPDATE platform_users SET display_name = ?, updated_at = ? WHERE id = ?",
                displayName, Timestamp.from(Instant.now()), userId);
    }

    @Override
    public Optional<UserProfile> findProfileByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT user_id, preferred_tone, timezone, summary, created_at, updated_at "
                        + "FROM platform_user_profiles WHERE user_id = ?",
                this::mapProfile,
                userId).stream().findFirst();
    }

    @Override
    public void saveProfile(UserProfile profile) {
        jdbcTemplate.update("""
                INSERT INTO platform_user_profiles (
                    user_id, preferred_tone, timezone, summary, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    preferred_tone = EXCLUDED.preferred_tone,
                    timezone = EXCLUDED.timezone,
                    summary = EXCLUDED.summary,
                    updated_at = EXCLUDED.updated_at
                """,
                profile.userId(), profile.preferredTone(), profile.timezone(), profile.summary(),
                Timestamp.from(profile.createdAt()), Timestamp.from(profile.updatedAt()));
    }

    private UserIdentity mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserIdentity(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("external_id"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Tenant mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("slug"),
                TenantStatus.valueOf(rs.getString("status")),
                rs.getString("creator_user_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("deletion_requested_at")));
    }

    private TenantMembership mapMembership(ResultSet rs, int rowNum) throws SQLException {
        return new TenantMembership(
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                TenantRole.valueOf(rs.getString("tenant_role")),
                TenantMembershipStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("joined_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private UserProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new UserProfile(
                rs.getString("user_id"),
                rs.getString("preferred_tone"),
                rs.getString("timezone"),
                rs.getString("summary"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
