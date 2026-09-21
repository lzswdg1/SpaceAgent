package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class PostgresRuntimeCleanupServiceTest {
    @Test
    void organizationPurgeDeletesJobsBeforeTheirExecutionRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new PostgresRuntimeCleanupService(jdbc).purgeOrganization("tenant-1");

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_run_handoffs"), eq("tenant-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_coding_jobs"), eq("tenant-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_plan_step_assignments"), eq("tenant-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_plan_executions"), eq("tenant-1"));
    }

    @Test
    void userPurgeDeletesJobsBeforeTheirExecutionRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new PostgresRuntimeCleanupService(jdbc).purgeUser("user-1");

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_run_handoffs"), eq("user-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_coding_jobs"), eq("user-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_plan_step_assignments"), eq("user-1"));
        ordered.verify(jdbc).update(
                startsWith("DELETE FROM platform_project_plan_executions"), eq("user-1"));
    }

    @Test
    void organizationQuiesceBlocksEveryNonTerminalControlState() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new PostgresRuntimeCleanupService(jdbc).quiesceOrganization("tenant-1");

        org.mockito.Mockito.verify(jdbc).update(
                contains("state IN('READY','RUNNING','PAUSING','PAUSED','CANCELLING')"),
                eq("tenant-1"));
    }
}
