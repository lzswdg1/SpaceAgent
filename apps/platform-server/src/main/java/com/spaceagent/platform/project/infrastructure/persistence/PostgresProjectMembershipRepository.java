package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectMembership;
import com.spaceagent.platform.project.domain.ProjectMembershipRepository;
import com.spaceagent.platform.project.domain.ProjectRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Authoritative PostgreSQL Project membership adapter. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectMembershipRepository implements ProjectMembershipRepository {

    private static final String COLUMNS = "id, project_id, user_id, role, created_at";

    private final JdbcTemplate jdbcTemplate;

    public PostgresProjectMembershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void addMember(ProjectMembership membership) {
        jdbcTemplate.update("""
                INSERT INTO platform_project_memberships (
                    id, project_id, user_id, role, created_at
                ) VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?)
                ON CONFLICT (project_id, user_id) DO UPDATE SET
                    role = EXCLUDED.role
                """,
                membership.id(), membership.projectId(), membership.userId(),
                membership.role().name(), Timestamp.from(membership.createdAt()));
    }

    @Override
    public boolean removeMember(String projectId, String userId) {
        return jdbcTemplate.update("""
                DELETE FROM platform_project_memberships
                WHERE project_id = CAST(? AS UUID) AND user_id = ?
                """, projectId, userId) > 0;
    }

    @Override
    public Optional<ProjectMembership> findMembership(String projectId, String userId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_project_memberships "
                        + "WHERE project_id = CAST(? AS UUID) AND user_id = ?",
                this::map,
                projectId,
                userId).stream().findFirst();
    }

    @Override
    public List<ProjectMembership> listMembers(String projectId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_project_memberships "
                        + "WHERE project_id = CAST(? AS UUID) ORDER BY created_at, user_id",
                this::map,
                projectId);
    }

    private ProjectMembership map(ResultSet rs, int rowNum) throws SQLException {
        return ProjectMembership.restore(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("user_id"),
                ProjectRole.valueOf(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant());
    }
}
