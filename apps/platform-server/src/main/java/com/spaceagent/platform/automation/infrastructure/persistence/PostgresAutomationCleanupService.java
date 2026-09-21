package com.spaceagent.platform.automation.infrastructure.persistence;

import com.spaceagent.platform.automation.api.AutomationCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAutomationCleanupService implements AutomationCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresAutomationCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("DELETE FROM platform_automation_trigger_dead_letters WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_dispatch_plans WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_trigger_deliveries WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_trigger_occurrences WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_trigger_subscriptions WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_triggers WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_executions WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_automation_schedules WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public UserCleanupView cleanupUser(String userId) {
        Long unknown = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM platform_automation_executions
                         WHERE owner_id = ? AND state = 'UNKNOWN')
                     + (SELECT count(*) FROM platform_automation_trigger_occurrences
                         WHERE owner_id = ? AND state = 'UNKNOWN')
                     + (SELECT count(*) FROM platform_automation_trigger_deliveries
                         WHERE owner_id = ? AND state = 'UNKNOWN')
                     + (SELECT count(*) FROM platform_automation_dispatch_plans
                         WHERE owner_id = ? AND phase = 'UNKNOWN')
                """, Long.class, userId, userId, userId, userId);
        if (unknown != null && unknown > 0L) {
            return new UserCleanupView(true, "UNKNOWN_AUTOMATION_EFFECT");
        }
        jdbc.update("DELETE FROM platform_automation_trigger_dead_letters WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_dispatch_plans WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_trigger_deliveries WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_trigger_occurrences WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_trigger_subscriptions WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_triggers WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_executions WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_automation_schedules WHERE owner_id = ?", userId);
        return new UserCleanupView(false, null);
    }
}
