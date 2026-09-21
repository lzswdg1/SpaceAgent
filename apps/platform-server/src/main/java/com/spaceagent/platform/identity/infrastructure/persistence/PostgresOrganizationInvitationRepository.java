package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.OrganizationInvitation;
import com.spaceagent.platform.identity.domain.OrganizationInvitationRepository;
import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
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
public class PostgresOrganizationInvitationRepository implements OrganizationInvitationRepository {

    private static final String COLUMNS = """
            id, organization_id, email, invitation_role, status, token_hash,
            invited_by_user_id, expires_at, accepted_by_user_id, created_at,
            updated_at, accepted_at, revoked_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresOrganizationInvitationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<OrganizationInvitation> findByTokenHash(String tokenHash) {
        return queryOne("SELECT " + COLUMNS
                + " FROM platform_organization_invitations WHERE token_hash = ?", tokenHash);
    }

    @Override
    public Optional<OrganizationInvitation> findByTokenHashForUpdate(String tokenHash) {
        return queryOne("SELECT " + COLUMNS
                + " FROM platform_organization_invitations WHERE token_hash = ? FOR UPDATE", tokenHash);
    }

    @Override
    public Optional<OrganizationInvitation> findByIdForUpdate(
            String organizationId,
            String invitationId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_organization_invitations "
                        + "WHERE organization_id = ? AND id = ? FOR UPDATE",
                this::map, organizationId, invitationId).stream().findFirst();
    }

    @Override
    public Optional<OrganizationInvitation> findPendingByOrganizationAndEmail(
            String organizationId,
            String email) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_organization_invitations "
                        + "WHERE organization_id = ? AND email = ? AND status = 'PENDING' FOR UPDATE",
                this::map, organizationId, email).stream().findFirst();
    }

    @Override
    public List<OrganizationInvitation> findByOrganization(String organizationId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM platform_organization_invitations "
                        + "WHERE organization_id = ? ORDER BY created_at DESC, id",
                this::map, organizationId);
    }

    @Override
    public boolean markAcceptedIfPending(String tokenHash, String userId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE platform_organization_invitations
                SET status = 'ACCEPTED', accepted_by_user_id = ?,
                    accepted_at = ?, updated_at = ?
                WHERE token_hash = ? AND status = 'PENDING' AND expires_at > ?
                """,
                userId, Timestamp.from(now), Timestamp.from(now), tokenHash,
                Timestamp.from(now)) == 1;
    }

    @Override
    public void save(OrganizationInvitation invitation) {
        jdbcTemplate.update("""
                INSERT INTO platform_organization_invitations (
                    id, organization_id, email, invitation_role, status, token_hash,
                    invited_by_user_id, expires_at, accepted_by_user_id, created_at,
                    updated_at, accepted_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    accepted_by_user_id = EXCLUDED.accepted_by_user_id,
                    updated_at = EXCLUDED.updated_at,
                    accepted_at = EXCLUDED.accepted_at,
                    revoked_at = EXCLUDED.revoked_at
                """,
                invitation.id(), invitation.organizationId(), invitation.email(),
                invitation.role().name(), invitation.status().name(), invitation.tokenHash(),
                invitation.invitedByUserId(), Timestamp.from(invitation.expiresAt()),
                invitation.acceptedByUserId(), Timestamp.from(invitation.createdAt()),
                Timestamp.from(invitation.updatedAt()), timestamp(invitation.acceptedAt()),
                timestamp(invitation.revokedAt()));
    }

    private Optional<OrganizationInvitation> queryOne(String sql, String value) {
        return jdbcTemplate.query(sql, this::map, value).stream().findFirst();
    }

    private OrganizationInvitation map(ResultSet rs, int rowNum) throws SQLException {
        return new OrganizationInvitation(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("email"),
                TenantRole.valueOf(rs.getString("invitation_role")),
                OrganizationInvitationStatus.valueOf(rs.getString("status")),
                rs.getString("token_hash"), rs.getString("invited_by_user_id"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("accepted_by_user_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("accepted_at")), instant(rs.getTimestamp("revoked_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
