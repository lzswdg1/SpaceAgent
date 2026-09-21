package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.api.ProjectCleanupApplicationApi;
import com.spaceagent.platform.project.domain.SourceRepositoryRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeWorkspaceGateway;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceProvisioningGateway;
import com.spaceagent.platform.project.domain.WorkspaceRepository;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunkStaging;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSnapshotGateway;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectCleanupService implements ProjectCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    private final WorkspaceRepository workspaces;
    private final SourceRepositoryRepository sources;
    private final WorkspaceProvisioningGateway gateway;
    private final ProjectIntakeWorkspaceGateway intakeGateway;
    private final ProjectLocalMaterializationChunkStaging staging;
    private final ProjectLocalMaterializationSnapshotGateway snapshots;

    public PostgresProjectCleanupService(
            JdbcTemplate jdbc,
            WorkspaceRepository workspaces,
            SourceRepositoryRepository sources,
            WorkspaceProvisioningGateway gateway,
            ProjectIntakeWorkspaceGateway intakeGateway) {
        this(jdbc,workspaces,sources,gateway,intakeGateway,null,null);
    }

    @Autowired
    public PostgresProjectCleanupService(JdbcTemplate jdbc,WorkspaceRepository workspaces,
            SourceRepositoryRepository sources,WorkspaceProvisioningGateway gateway,
            ProjectIntakeWorkspaceGateway intakeGateway,ProjectLocalMaterializationChunkStaging staging,
            ProjectLocalMaterializationSnapshotGateway snapshots) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.sources = sources;
        this.gateway = gateway;
        this.intakeGateway = intakeGateway;
        this.staging=staging;this.snapshots=snapshots;
    }

    @Override @Transactional(readOnly = true)
    public CleanupMemoryScopes resolveCleanupMemoryScopes(String organizationId) {
        List<String> projectIds = jdbc.queryForList(
                "SELECT id::text FROM platform_projects WHERE tenant_id = ? ORDER BY id",
                String.class, organizationId);
        List<String> taskIds = jdbc.queryForList("""
                SELECT task.id::text FROM platform_tasks task
                JOIN platform_projects project ON project.id = task.project_id
                WHERE project.tenant_id = ? ORDER BY task.id
                """, String.class, organizationId);
        return new CleanupMemoryScopes(projectIds, taskIds);
    }

    @Override @Transactional(readOnly=true)
    public boolean hasRetainedCodeStorage(String organizationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_source_repositories WHERE tenant_id=?)
                    OR EXISTS(SELECT 1 FROM platform_workspaces WHERE tenant_id=?)
                """,Boolean.class,organizationId,organizationId));
    }

    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("DELETE FROM platform_source_merge_jobs WHERE tenant_id = ?", organizationId);
        cleanupIntakeWorkspaces("tenant_id", organizationId);
        List<String> workspaceIds = jdbc.queryForList(
                "SELECT id::text FROM platform_workspaces WHERE tenant_id = ? ORDER BY created_at, id",
                String.class, organizationId);
        for (String id : workspaceIds) {
            var workspace = workspaces.findById(id).orElseThrow();
            var source = sources.findById(workspace.sourceRepositoryId()).orElseThrow();
            if (workspace.mode() == WorkspaceMode.MANAGED_GIT) {
                gateway.cleanup(workspace, source);
            }
        }
        List<String> sourceIds = jdbc.queryForList(
                "SELECT id::text FROM platform_source_repositories WHERE tenant_id = ? ORDER BY created_at, id",
                String.class, organizationId);
        for (String id : sourceIds) {
            var source=sources.findById(id).orElseThrow();
            if(source.type()==SourceRepositoryType.MANAGED_SNAPSHOT){
                if(snapshots==null)throw new IllegalStateException("MANAGED_SNAPSHOT_CLEANUP_UNAVAILABLE");
                snapshots.deleteSnapshot(source.snapshotRef());
            }else gateway.cleanupSource(source);
        }
        if(staging==null&&!jdbc.queryForList("SELECT id FROM platform_project_local_materialization_sessions WHERE tenant_id=? LIMIT 1",organizationId).isEmpty())throw new IllegalStateException("MATERIALIZATION_STAGING_CLEANUP_UNAVAILABLE");
        if(staging!=null)for(String id:jdbc.queryForList("SELECT id::text FROM platform_project_local_materialization_sessions WHERE tenant_id=? ORDER BY id",String.class,organizationId))staging.cleanupSession(id);

        jdbc.update("""
                DELETE FROM platform_bridge_workspace_commands command
                USING platform_workspaces workspace
                WHERE command.workspace_id = workspace.id AND workspace.tenant_id = ?
                """, organizationId);
        jdbc.update("DELETE FROM platform_workspaces WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_intake_jobs WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_blueprints WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_directories WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_source_repositories WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_local_materialization_chunks WHERE session_id IN (SELECT id FROM platform_project_local_materialization_sessions WHERE tenant_id=?)",organizationId);
        jdbc.update("DELETE FROM platform_project_local_materialization_sessions WHERE tenant_id = ?",organizationId);
        jdbc.update("DELETE FROM platform_github_oauth_states WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_github_connections WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_local_workspace_bridges WHERE tenant_id = ?", organizationId);
        jdbc.update("""
                DELETE FROM platform_plan_step_dependencies dependency
                USING platform_task_plans plan, platform_projects project
                WHERE dependency.task_plan_id = plan.id AND plan.project_id = project.id
                  AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                UPDATE platform_tasks task SET current_task_plan_id = NULL
                FROM platform_projects project
                WHERE task.project_id = project.id AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                DELETE FROM platform_plan_steps step
                USING platform_task_plans plan, platform_projects project
                WHERE step.task_plan_id = plan.id AND plan.project_id = project.id
                  AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                DELETE FROM platform_task_plans plan
                USING platform_projects project
                WHERE plan.project_id = project.id AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                DELETE FROM platform_tasks task USING platform_projects project
                WHERE task.project_id = project.id AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("""
                DELETE FROM platform_project_memberships membership USING platform_projects project
                WHERE membership.project_id = project.id AND project.tenant_id = ?
                """, organizationId);
        jdbc.update("DELETE FROM platform_projects WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupUser(String userId) {
        Long blockers = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM platform_projects WHERE owner_id = ?)
                     + (SELECT count(*) FROM platform_source_repositories WHERE created_by = ?)
                     + (SELECT count(*) FROM platform_workspaces WHERE created_by = ?
                         AND state NOT IN ('ARCHIVED','CLEANED_UP','FAILED'))
                """, Long.class, userId, userId, userId);
        if (blockers != null && blockers > 0L) {
            throw new IllegalStateException("USER_OWNS_PROJECT_RESOURCES");
        }
        cleanupIntakeWorkspaces("owner_id", userId);
        jdbc.update("DELETE FROM platform_github_oauth_states WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_github_connections WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_local_workspace_bridges WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_project_intake_jobs WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_project_memberships WHERE user_id = ?", userId);
    }

    private void cleanupIntakeWorkspaces(String column, String value) {
        if (!"tenant_id".equals(column) && !"owner_id".equals(column)) {
            throw new IllegalArgumentException("Unsupported intake cleanup scope");
        }
        List<java.util.Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id::text AS id, source_repository_id::text AS source_id "
                        + "FROM platform_project_intake_jobs WHERE " + column + " = ? "
                        + "AND workspace_ref IS NOT NULL AND workspace_cleaned_at IS NULL "
                        + "ORDER BY created_at,id",
                value);
        for (var candidate : candidates) {
            String sourceId = candidate.get("source_id").toString();
            sources.findById(sourceId).ifPresent(source -> intakeGateway.cleanup(
                    candidate.get("id").toString(), source));
        }
    }
}
