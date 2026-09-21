package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
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
public class PostgresProjectDirectoryRepository implements ProjectDirectoryRepository {
    private final JdbcTemplate jdbc;

    public PostgresProjectDirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ProjectDirectory value) {
        jdbc.update("""
                INSERT INTO platform_project_directories(
                    id, tenant_id, project_id, source_repository_id, name, relative_path,
                    is_default, state, created_by, created_at, updated_at)
                VALUES(CAST(? AS UUID), ?, CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = EXCLUDED.name, source_repository_id = EXCLUDED.source_repository_id,
                    state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                """, value.id(), value.tenantId(), value.projectId(), value.sourceRepositoryId(),
                value.name(), value.relativePath(), value.defaultDirectory(), value.state().name(),
                value.createdBy(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
    }

    @Override
    public Optional<ProjectDirectory> findById(String id) {
        return jdbc.query("SELECT * FROM platform_project_directories WHERE id = CAST(? AS UUID)",
                this::map, id).stream().findFirst();
    }

    @Override
    public Optional<ProjectDirectory> findByIdForUpdate(String id) {
        return jdbc.query("SELECT * FROM platform_project_directories WHERE id=CAST(? AS UUID) FOR UPDATE",this::map,id).stream().findFirst();
    }

    @Override
    public List<ProjectDirectory> findPendingManagedRoots() {
        return jdbc.query("""
                SELECT directory.* FROM platform_project_directories directory
                JOIN platform_source_repositories source ON source.id=directory.source_repository_id
                WHERE directory.state='ACTIVE' AND source.repository_type='GENERIC'
                  AND source.remote_url IS NULL AND source.state='PROVISIONING'
                ORDER BY directory.created_at LIMIT 500
                """,this::map);
    }

    @Override
    public Optional<ProjectDirectory> findDefault(String projectId) {
        return jdbc.query("""
                SELECT * FROM platform_project_directories
                 WHERE project_id = CAST(? AS UUID) AND is_default
                """, this::map, projectId).stream().findFirst();
    }

    @Override
    public Optional<ProjectDirectory> findBySourceAndPath(
            String projectId, String sourceRepositoryId, String relativePath) {
        return jdbc.query("""
                SELECT * FROM platform_project_directories
                 WHERE project_id = CAST(? AS UUID)
                   AND source_repository_id = CAST(? AS UUID) AND relative_path = ?
                 ORDER BY CASE state WHEN 'ACTIVE' THEN 0 ELSE 1 END, created_at DESC
                 LIMIT 1
                """, this::map, projectId, sourceRepositoryId, relativePath).stream().findFirst();
    }

    @Override
    public List<ProjectDirectory> findByProjectId(String projectId) {
        return jdbc.query("""
                SELECT * FROM platform_project_directories
                 WHERE project_id = CAST(? AS UUID)
                 ORDER BY is_default DESC, name, id
                """, this::map, projectId);
    }

    private ProjectDirectory map(ResultSet result, int row) throws SQLException {
        return new ProjectDirectory(
                result.getString("id"), result.getString("tenant_id"),
                result.getString("project_id"), result.getString("source_repository_id"),
                result.getString("name"), result.getString("relative_path"),
                result.getBoolean("is_default"),
                ProjectDirectoryState.valueOf(result.getString("state")),
                result.getString("created_by"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }
}
