package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRepository;
import com.spaceagent.platform.project.domain.ProjectStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Authoritative PostgreSQL Project aggregate adapter. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectRepository implements ProjectRepository {

    private static final String COLUMNS = """
            id, tenant_id, owner_id, name, description, status, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(Project project) {
        jdbcTemplate.update("""
                INSERT INTO platform_projects (
                    id, tenant_id, owner_id, name, description, status, created_at, updated_at
                ) VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                project.id(), project.tenantId(), project.ownerId(), project.name(),
                project.description(), project.status().name(),
                Timestamp.from(project.createdAt()), Timestamp.from(project.updatedAt()));
    }

    @Override
    public Optional<Project> findById(String projectId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_projects WHERE id = CAST(? AS UUID)",
                this::map,
                projectId).stream().findFirst();
    }

    @Override
    public List<Project> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_projects "
                        + "WHERE tenant_id = ? ORDER BY updated_at DESC, id",
                this::map,
                tenantId);
    }

    @Override
    public boolean exists(String tenantId, String name) {
        Boolean found = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_projects WHERE tenant_id = ? AND name = ?
                )
                """, Boolean.class, tenantId, name);
        return Boolean.TRUE.equals(found);
    }

    private Project map(ResultSet rs, int rowNum) throws SQLException {
        return Project.restore(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("owner_id"),
                rs.getString("name"),
                rs.getString("description"),
                ProjectStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
