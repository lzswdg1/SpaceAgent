package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresSourceRepositoryRepository implements SourceRepositoryRepository {
    private static final String COLUMNS = """
            id, project_id, tenant_id, mcp_connection_id, mcp_invocation_id,
            workspace_bridge_id,
            provider_repository_id, display_name, remote_url, local_root_handle,
            default_branch, repository_type, state, visibility, created_by, created_at, updated_at,
            materialization_session_id,snapshot_ref,manifest_sha256,content_sha256,finalize_request_id
            """;
    private final JdbcTemplate jdbc;
    public PostgresSourceRepositoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(SourceRepository value) {
        jdbc.update("""
                INSERT INTO platform_source_repositories (
                    id, project_id, tenant_id, mcp_connection_id, mcp_invocation_id,
                    workspace_bridge_id,
                    provider_repository_id, display_name, remote_url, local_root_handle,
                    default_branch, repository_type, state, visibility, created_by, created_at, updated_at,
                    materialization_session_id,snapshot_ref,manifest_sha256,content_sha256,finalize_request_id
                ) VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID),
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,CAST(? AS UUID),?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET state=EXCLUDED.state, updated_at=EXCLUDED.updated_at
                """, value.id(), value.projectId(), value.tenantId(),
                value.mcpConnectionId(), value.mcpInvocationId(), value.workspaceBridgeId(),
                value.providerRepositoryId(), value.displayName(),
                value.remoteUrl(), value.localRootHandle(), value.defaultBranch(), value.type().name(),
                value.state().name(), value.visibility().name(), value.createdBy(),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()),
                value.materializationSessionId(), value.snapshotRef(), value.manifestSha256(),
                value.contentSha256(), value.finalizeRequestId());
    }
    public Optional<SourceRepository> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_source_repositories WHERE id=CAST(? AS UUID)", this::map, id).stream().findFirst();
    }
    public List<SourceRepository> findByProjectId(String projectId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_source_repositories WHERE project_id=CAST(? AS UUID) ORDER BY created_at,id", this::map, projectId);
    }
    @Override public List<SourceRepository> pageByProjectId(String projectId,int offset,int limit){
        return jdbc.query("SELECT "+COLUMNS+" FROM platform_source_repositories WHERE project_id=CAST(? AS UUID) ORDER BY created_at,id LIMIT ? OFFSET ?",this::map,projectId,limit,offset);
    }
    public boolean existsGithub(String projectId, String providerRepositoryId) {
        return findActiveGithub(projectId, providerRepositoryId).isPresent();
    }
    public Optional<SourceRepository> findActiveGithub(String projectId, String providerRepositoryId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_source_repositories WHERE project_id=CAST(? AS UUID) AND provider_repository_id=? AND repository_type='GITHUB' AND state<>'ARCHIVED'", this::map, projectId, providerRepositoryId).stream().findFirst();
    }
    public Optional<SourceRepository> findByMaterializationSessionId(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_source_repositories WHERE materialization_session_id=CAST(? AS UUID)", this::map, id).stream().findFirst();
    }
    public boolean existsLocal(String projectId, String bridgeId, String rootHandle) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_source_repositories
                 WHERE project_id=CAST(? AS UUID) AND workspace_bridge_id=CAST(? AS UUID)
                   AND local_root_handle=? AND repository_type='LOCAL' AND state<>'ARCHIVED')
                """, Boolean.class, projectId, bridgeId, rootHandle);
        return Boolean.TRUE.equals(found);
    }
    private boolean exists(String where, Object... args) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_source_repositories WHERE " + where + ")", Boolean.class, args);
        return Boolean.TRUE.equals(found);
    }
    private SourceRepository map(ResultSet rs, int row) throws SQLException {
        return new SourceRepository(
                rs.getString("id"), rs.getString("project_id"), rs.getString("tenant_id"),
                rs.getString("mcp_connection_id"),
                rs.getString("mcp_invocation_id"),
                rs.getString("workspace_bridge_id"), rs.getString("provider_repository_id"),
                rs.getString("display_name"), rs.getString("remote_url"),
                rs.getString("local_root_handle"), rs.getString("default_branch"),
                SourceRepositoryType.valueOf(rs.getString("repository_type")),
                SourceRepositoryState.valueOf(rs.getString("state")),
                SourceRepositoryVisibility.valueOf(rs.getString("visibility")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("materialization_session_id"),
                rs.getString("snapshot_ref"), rs.getString("manifest_sha256"),
                rs.getString("content_sha256"), rs.getString("finalize_request_id"));
    }
}
