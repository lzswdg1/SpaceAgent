package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectSystemAdministrationQuery implements ProjectSystemAdministrationQuery {
    private final JdbcTemplate jdbc;
    public PostgresProjectSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OverviewRow overview() {
        return jdbc.queryForObject("""
                SELECT count(*) projects,
                       count(*) FILTER (WHERE status = 'ACTIVE') active_projects,
                       (SELECT count(*) FROM platform_workspaces) workspaces,
                       (SELECT count(*) FROM platform_workspaces
                         WHERE state NOT IN ('ARCHIVED', 'CLEANED_UP', 'FAILED')) active_workspaces
                FROM platform_projects
                """, (rs, row) -> new OverviewRow(rs.getLong("projects"),
                rs.getLong("active_projects"), rs.getLong("workspaces"),
                rs.getLong("active_workspaces")));
    }

    @Override
    public PageRows<ResourceRow> projectsByOwner(String userId, int offset, int limit) {
        return page("""
                SELECT project.id::text id, project.tenant_id, NULL::text parent_id,
                       project.name, project.status, 'OWNER'::text relation,
                       project.created_at, project.updated_at, NULL::text safe_error_code,
                       (SELECT count(*) FROM platform_tasks task
                         WHERE task.project_id = project.id) primary_count,
                       (SELECT count(*) FROM platform_workspaces workspace
                         WHERE workspace.project_id = project.id) secondary_count
                FROM platform_projects project WHERE project.owner_id = ?
                ORDER BY project.updated_at DESC, project.id LIMIT ? OFFSET ?
                """, "SELECT count(*) FROM platform_projects WHERE owner_id = ?",
                userId, offset, limit);
    }

    @Override
    public PageRows<ResourceRow> tasksByOwner(String userId, int offset, int limit) {
        return page("""
                SELECT task.id::text id, project.tenant_id, task.project_id::text parent_id,
                       NULL::text name, task.state status,
                       CASE WHEN task.parent_task_id IS NULL THEN 'ROOT' ELSE 'CHILD' END relation,
                       task.created_at, task.updated_at, NULL::text safe_error_code,
                       (SELECT count(*) FROM platform_tasks child
                         WHERE child.parent_task_id = task.id) primary_count,
                       (SELECT count(*) FROM platform_workspaces workspace
                         WHERE workspace.task_id = task.id) secondary_count
                FROM platform_tasks task
                JOIN platform_projects project ON project.id = task.project_id
                WHERE project.owner_id = ?
                ORDER BY task.updated_at DESC, task.id LIMIT ? OFFSET ?
                """, """
                SELECT count(*) FROM platform_tasks task
                JOIN platform_projects project ON project.id = task.project_id
                WHERE project.owner_id = ?
                """, userId, offset, limit);
    }

    @Override
    public PageRows<ResourceRow> workspacesByOwner(String userId, int offset, int limit) {
        return page("""
                SELECT workspace.id::text id, workspace.tenant_id,
                       workspace.project_id::text parent_id, NULL::text name,
                       workspace.state status, workspace.mode relation,
                       workspace.created_at, workspace.updated_at,
                       CASE WHEN workspace.state = 'FAILED' THEN 'WORKSPACE_FAILED' END safe_error_code,
                       CASE WHEN workspace.writable THEN 1 ELSE 0 END::bigint primary_count,
                       0::bigint secondary_count
                FROM platform_workspaces workspace WHERE workspace.created_by = ?
                ORDER BY workspace.updated_at DESC, workspace.id LIMIT ? OFFSET ?
                """, "SELECT count(*) FROM platform_workspaces WHERE created_by = ?",
                userId, offset, limit);
    }

    private PageRows<ResourceRow> page(
            String sql, String countSql, String userId, int offset, int limit) {
        var items = jdbc.query(sql, (rs, row) -> new ResourceRow(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("parent_id"),
                rs.getString("name"), rs.getString("status"), rs.getString("relation"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("primary_count"), rs.getLong("secondary_count")), userId, limit, offset);
        Long total = jdbc.queryForObject(countSql, Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public DeletionEvidenceRow deletionEvidence(String userId) {
        return jdbc.queryForObject("""
                SELECT
                  count(*) owned_projects,
                  count(*) FILTER (WHERE (
                    SELECT count(*) FROM platform_project_memberships membership
                    WHERE membership.project_id = project.id) > 1) shared_owned_projects,
                  (SELECT count(*) FROM platform_workspaces workspace
                    WHERE workspace.created_by = ?
                      AND workspace.state NOT IN ('ARCHIVED','CLEANED_UP','FAILED')) active_workspaces
                FROM platform_projects project WHERE project.owner_id = ?
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("owned_projects"),
                rs.getLong("shared_owned_projects"), rs.getLong("active_workspaces")), userId, userId);
    }
}
