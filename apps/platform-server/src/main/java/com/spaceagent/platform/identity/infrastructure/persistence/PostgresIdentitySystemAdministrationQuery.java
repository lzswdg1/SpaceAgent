package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentitySystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentitySystemAdministrationQuery implements IdentitySystemAdministrationQuery {
    private static final String USER_SELECT = """
            SELECT user_row.id, user_row.tenant_id, COALESCE(credential.username, user_row.external_id) login_name,
                   user_row.display_name, user_row.status, user_row.must_change_password,
                   user_row.created_at, user_row.updated_at, activity.last_login_at,
                   activity.last_seen_at, COALESCE(activity.successful_login_count, 0) successful_login_count,
                   (SELECT count(*) FROM platform_refresh_tokens refresh
                     WHERE refresh.user_id = user_row.id AND refresh.revoked_at IS NULL
                       AND refresh.expires_at > ?) active_refresh_sessions,
                   (SELECT count(*) FROM platform_tenant_memberships membership
                     WHERE membership.user_id = user_row.id AND membership.status = 'ACTIVE') membership_count
            FROM platform_users user_row
            LEFT JOIN platform_user_credentials credential ON credential.user_id = user_row.id
            LEFT JOIN platform_user_activity activity ON activity.user_id = user_row.id
            """;

    private final JdbcTemplate jdbc;

    public PostgresIdentitySystemAdministrationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OverviewRow overview(
            Instant registeredSince,
            Instant loginSince,
            Instant recentlyActiveSince,
            Instant now) {
        return jdbc.queryForObject("""
                SELECT
                  count(*) total_users,
                  count(*) FILTER (WHERE status = 'PENDING_ACTIVATION') pending_users,
                  count(*) FILTER (WHERE status = 'ACTIVE') active_users,
                  count(*) FILTER (WHERE status = 'SUSPENDED') suspended_users,
                  count(*) FILTER (WHERE status = 'DELETION_PENDING') deletion_pending_users,
                  count(*) FILTER (WHERE status = 'DELETED') deleted_users,
                  count(*) FILTER (WHERE created_at >= ?) registered_window,
                  (SELECT count(DISTINCT user_id) FROM platform_auth_events
                    WHERE event_type = 'LOGIN_SUCCEEDED' AND occurred_at >= ?) unique_logins,
                  (SELECT count(*) FROM platform_user_activity
                    WHERE last_seen_at >= ?) recently_active,
                  (SELECT count(*) FROM platform_refresh_tokens
                    WHERE revoked_at IS NULL AND expires_at > ?) active_sessions,
                  (SELECT count(*) FROM platform_tenants WHERE status = 'ACTIVE') active_orgs,
                  (SELECT count(*) FROM platform_tenants WHERE status = 'DELETING') deleting_orgs
                FROM platform_users
                """, (rs, row) -> new OverviewRow(rs.getLong("total_users"),
                        rs.getLong("pending_users"), rs.getLong("active_users"),
                        rs.getLong("suspended_users"), rs.getLong("deletion_pending_users"),
                        rs.getLong("deleted_users"), rs.getLong("registered_window"),
                        rs.getLong("unique_logins"), rs.getLong("recently_active"),
                        rs.getLong("active_sessions"), rs.getLong("active_orgs"),
                        rs.getLong("deleting_orgs")), Timestamp.from(registeredSince),
                Timestamp.from(loginSince), Timestamp.from(recentlyActiveSince), Timestamp.from(now));
    }

    @Override
    public PageRows<UserRow> users(
            int offset, int limit, String text, String status, UserSort sort, Instant now) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> filters = new ArrayList<>();
        if (text != null) {
            where.append(" AND (lower(COALESCE(credential.username, user_row.external_id)) LIKE lower(?)")
                    .append(" OR lower(user_row.display_name) LIKE lower(?))");
            String pattern = "%" + text + "%";
            filters.add(pattern);
            filters.add(pattern);
        }
        if (status != null) {
            where.append(" AND user_row.status = ?");
            filters.add(status);
        }
        String order = switch (sort) {
            case LAST_LOGIN_DESC -> " ORDER BY activity.last_login_at DESC NULLS LAST, user_row.id ";
            case LAST_SEEN_DESC -> " ORDER BY activity.last_seen_at DESC NULLS LAST, user_row.id ";
            case LOGIN_ASC -> " ORDER BY lower(COALESCE(credential.username, user_row.external_id)), user_row.id ";
            default -> " ORDER BY user_row.created_at DESC, user_row.id ";
        };
        List<Object> pageArguments = new ArrayList<>();
        pageArguments.add(Timestamp.from(now));
        pageArguments.addAll(filters);
        pageArguments.add(limit);
        pageArguments.add(offset);
        List<UserRow> items = jdbc.query(USER_SELECT + where + order + " LIMIT ? OFFSET ?",
                this::mapUser, pageArguments.toArray());
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM platform_users user_row
                LEFT JOIN platform_user_credentials credential ON credential.user_id = user_row.id
                """ + where, Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public Optional<UserRow> user(String userId, Instant now) {
        return jdbc.query(USER_SELECT + " WHERE user_row.id = ?", this::mapUser,
                Timestamp.from(now), userId).stream().findFirst();
    }

    @Override
    public List<MembershipRow> memberships(String userId) {
        return jdbc.query("""
                SELECT membership.tenant_id, tenant.name, tenant.status, membership.tenant_role,
                       membership.status membership_status, membership.joined_at
                FROM platform_tenant_memberships membership
                JOIN platform_tenants tenant ON tenant.id = membership.tenant_id
                WHERE membership.user_id = ?
                ORDER BY membership.joined_at, membership.tenant_id
                """, (rs, row) -> new MembershipRow(rs.getString("tenant_id"), rs.getString("name"),
                        rs.getString("status"), rs.getString("tenant_role"),
                        rs.getString("membership_status"), instant(rs, "joined_at")), userId);
    }

    @Override
    public PageRows<OrganizationRow> organizations(
            int offset, int limit, String text, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> filters = new ArrayList<>();
        if (text != null) {
            where.append(" AND (lower(tenant.name) LIKE lower(?) OR lower(tenant.slug) LIKE lower(?))");
            String pattern = "%" + text + "%";
            filters.add(pattern);
            filters.add(pattern);
        }
        if (status != null) {
            where.append(" AND tenant.status = ?");
            filters.add(status);
        }
        List<Object> pageArguments = new ArrayList<>(filters);
        pageArguments.add(limit);
        pageArguments.add(offset);
        List<OrganizationRow> items = jdbc.query("""
                SELECT tenant.id, tenant.name, tenant.slug, tenant.status, tenant.creator_user_id,
                       creator.display_name creator_display_name,
                       count(membership.user_id) FILTER (WHERE membership.status = 'ACTIVE') active_members,
                       tenant.created_at, tenant.updated_at, tenant.deletion_requested_at
                FROM platform_tenants tenant
                LEFT JOIN platform_users creator ON creator.id = tenant.creator_user_id
                LEFT JOIN platform_tenant_memberships membership ON membership.tenant_id = tenant.id
                """ + where + " " + """
                GROUP BY tenant.id, creator.display_name
                ORDER BY tenant.created_at DESC, tenant.id
                LIMIT ? OFFSET ?
                """, this::mapOrganization, pageArguments.toArray());
        Long total = jdbc.queryForObject("SELECT count(*) FROM platform_tenants tenant " + where,
                Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public boolean organizationExists(String organizationId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform_tenants WHERE id = ?)",
                Boolean.class, organizationId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public PageRows<OrganizationMemberRow> organizationMembers(
            String organizationId, int offset, int limit, String text,
            String membershipStatus, String role) {
        StringBuilder where = new StringBuilder(" WHERE membership.tenant_id = ? ");
        List<Object> filters = new ArrayList<>();
        filters.add(organizationId);
        if (text != null) {
            where.append(" AND (lower(COALESCE(credential.username, user_row.external_id)) LIKE lower(?)")
                    .append(" OR lower(user_row.display_name) LIKE lower(?) OR user_row.id = ?) ");
            String pattern = "%" + text + "%";
            filters.add(pattern);
            filters.add(pattern);
            filters.add(text);
        }
        if (membershipStatus != null) {
            where.append(" AND membership.status = ? ");
            filters.add(membershipStatus);
        }
        if (role != null) {
            where.append(" AND membership.tenant_role = ? ");
            filters.add(role);
        }
        List<Object> pageArguments = new ArrayList<>(filters);
        pageArguments.add(limit);
        pageArguments.add(offset);
        List<OrganizationMemberRow> items = jdbc.query("""
                SELECT membership.tenant_id, membership.user_id,
                       COALESCE(credential.username, user_row.external_id) login_name,
                       user_row.display_name, user_row.status user_status,
                       membership.tenant_role, membership.status membership_status,
                       membership.joined_at, membership.updated_at
                FROM platform_tenant_memberships membership
                JOIN platform_users user_row ON user_row.id = membership.user_id
                LEFT JOIN platform_user_credentials credential ON credential.user_id = user_row.id
                """ + where + " " + """
                ORDER BY CASE membership.tenant_role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1
                         WHEN 'MEMBER' THEN 2 ELSE 3 END,
                         CASE membership.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                         membership.joined_at, membership.user_id
                LIMIT ? OFFSET ?
                """, this::mapOrganizationMember, pageArguments.toArray());
        Long total = jdbc.queryForObject("""
                SELECT count(*)
                FROM platform_tenant_memberships membership
                JOIN platform_users user_row ON user_row.id = membership.user_id
                LEFT JOIN platform_user_credentials credential ON credential.user_id = user_row.id
                """ + where, Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public Optional<DeletionEvidenceRow> deletionEvidence(String userId) {
        Optional<String> status = jdbc.query("SELECT status FROM platform_users WHERE id = ?",
                (rs, row) -> rs.getString("status"), userId).stream().findFirst();
        if (status.isEmpty()) return Optional.empty();
        Long memberships = jdbc.queryForObject("""
                SELECT count(*) FROM platform_tenant_memberships
                WHERE user_id = ? AND status = 'ACTIVE'
                """, Long.class, userId);
        List<OwnedOrganizationRow> organizations = jdbc.query("""
                SELECT tenant.id, tenant.name, tenant.status,
                       count(membership.user_id) FILTER (WHERE membership.status = 'ACTIVE') active_members
                FROM platform_tenants tenant
                LEFT JOIN platform_tenant_memberships membership ON membership.tenant_id = tenant.id
                WHERE tenant.creator_user_id = ? AND tenant.status <> 'DELETED'
                GROUP BY tenant.id ORDER BY tenant.created_at, tenant.id
                """, (rs, row) -> new OwnedOrganizationRow(rs.getString("id"), rs.getString("name"),
                rs.getString("status"), rs.getLong("active_members")), userId);
        return Optional.of(new DeletionEvidenceRow(userId, status.get(),
                memberships == null ? 0 : memberships, organizations));
    }

    private UserRow mapUser(ResultSet rs, int row) throws SQLException {
        return new UserRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("login_name"), rs.getString("display_name"), rs.getString("status"),
                rs.getBoolean("must_change_password"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "last_login_at"),
                nullableInstant(rs, "last_seen_at"), rs.getLong("successful_login_count"),
                rs.getLong("active_refresh_sessions"), rs.getLong("membership_count"));
    }

    private OrganizationRow mapOrganization(ResultSet rs, int row) throws SQLException {
        return new OrganizationRow(rs.getString("id"), rs.getString("name"), rs.getString("slug"),
                rs.getString("status"), rs.getString("creator_user_id"),
                rs.getString("creator_display_name"), rs.getLong("active_members"),
                instant(rs, "created_at"), instant(rs, "updated_at"),
                nullableInstant(rs, "deletion_requested_at"));
    }

    private OrganizationMemberRow mapOrganizationMember(ResultSet rs, int row) throws SQLException {
        return new OrganizationMemberRow(rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("login_name"), rs.getString("display_name"),
                rs.getString("user_status"), rs.getString("tenant_role"),
                rs.getString("membership_status"), instant(rs, "joined_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
