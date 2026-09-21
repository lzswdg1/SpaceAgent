package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.api.ProjectOwnershipPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed Project authorization boundary used by scoped consumers. */
@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectOwnershipAdapter implements ProjectOwnershipPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresProjectOwnershipAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isOwnerOrMember(String projectId, String principalId) {
        if (!isUuid(projectId) || principalId == null || principalId.isBlank()) {
            return false;
        }
        Boolean authorized = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform_projects project
                    JOIN platform_project_memberships membership
                      ON membership.project_id = project.id
                    JOIN platform_tenant_memberships tenant_membership
                      ON tenant_membership.tenant_id = project.tenant_id
                     AND tenant_membership.user_id = membership.user_id
                     AND tenant_membership.status = 'ACTIVE'
                    WHERE project.id = CAST(? AS UUID)
                      AND membership.user_id = ?
                      AND membership.role IN ('OWNER', 'ADMIN', 'MEMBER')
                )
                """, Boolean.class, projectId, principalId);
        return Boolean.TRUE.equals(authorized);
    }

    @Override
    public Optional<String> findProjectIdByTask(String taskId) {
        if (!isUuid(taskId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT project_id FROM platform_tasks "
                        + "WHERE id = CAST(? AS UUID) AND project_id IS NOT NULL",
                (rs, rowNum) -> rs.getString("project_id"),
                taskId).stream().findFirst();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    @Override
    public Optional<String> findTenantIdByProject(String projectId) {
        if (!isUuid(projectId)) return Optional.empty();
        return jdbcTemplate.query("SELECT tenant_id FROM platform_projects WHERE id=CAST(? AS UUID)",
                (rs, row) -> rs.getString("tenant_id"), projectId).stream().findFirst();
    }
}
