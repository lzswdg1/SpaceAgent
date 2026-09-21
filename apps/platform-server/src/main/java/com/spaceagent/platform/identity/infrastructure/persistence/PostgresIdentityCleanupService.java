package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.api.IdentityCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityCleanupService implements IdentityCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final TimeProvider time;
    public PostgresIdentityCleanupService(
            JdbcTemplate jdbc, IdentityRepository identities, TimeProvider time) {
        this.jdbc = jdbc; this.identities = identities; this.time = time;
    }
    @Override @Transactional
    public void finalizeOrganization(String organizationId) {
        var organization = identities.findTenantByIdForUpdate(organizationId).orElseThrow();
        if (organization.status() == TenantStatus.DELETED) return;
        if (organization.status() != TenantStatus.DELETING
                || identities.countActiveMemberships(organizationId) != 0L) {
            throw new IllegalStateException("Organization is not ready for final cleanup");
        }
        jdbc.update("DELETE FROM platform_refresh_tokens WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_organization_invitations WHERE organization_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_tenant_memberships WHERE tenant_id = ?", organizationId);
        identities.saveTenant(organization.markDeleted(time.now()));
    }
}
