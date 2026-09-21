package com.spaceagent.platform.governance.infrastructure.persistence;

import com.spaceagent.platform.governance.api.GovernanceCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresGovernanceCleanupService implements GovernanceCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresGovernanceCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("DELETE FROM platform_approval_requests WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_governance_policies WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupUser(String userId) {
        jdbc.update("""
                UPDATE platform_approval_requests
                   SET state = 'CANCELLED', updated_at = clock_timestamp(), revision = revision + 1
                 WHERE requested_by = ? AND state = 'PENDING'
                """, userId);
    }
}
