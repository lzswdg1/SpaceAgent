package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.GetProjectQuery;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectOwnershipAdapter;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PlatformProjectPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_project_foundation")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrateAndSeedIdentity() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        seedTenant(jdbc, "tenant-a", "Tenant A");
        seedTenant(jdbc, "tenant-b", "Tenant B");
        seedUser(jdbc, "owner-a", "tenant-a");
        seedUser(jdbc, "admin-a", "tenant-a");
        seedUser(jdbc, "viewer-a", "tenant-a");
        seedUser(jdbc, "owner-b", "tenant-b");
    }

    @Test
    void migrationRepositoriesAndOwnershipRemainDurableInPostgres() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        PostgresProjectRepository projects = new PostgresProjectRepository(jdbc);
        PostgresProjectMembershipRepository memberships =
                new PostgresProjectMembershipRepository(jdbc);
        IdentityOwnershipPort identityOwnership = (tenantId, principalId) -> Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM platform_tenant_memberships
                            WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                        )
                        """, Boolean.class, tenantId, principalId));
        ProjectApplicationService service = new ProjectApplicationService(
                projects, memberships,
                new ProjectAccessPolicy(projects, memberships, identityOwnership, (t, u, w) -> {}),
                new UuidGenerator(), (TimeProvider) () -> NOW);

        var project = service.createProject(new CreateProjectCommand(
                "tenant-a", "owner-a", "Persistent Project", "PostgreSQL proof"));
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner-a", project.id(), "admin-a", ProjectRole.ADMIN));
        service.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner-a", project.id(), "viewer-a", ProjectRole.VIEWER));

        PostgresProjectRepository reconnectedProjects = new PostgresProjectRepository(
                new JdbcTemplate(newDataSource()));
        var reloaded = reconnectedProjects.findById(project.id()).orElseThrow();
        assertEquals("tenant-a", reloaded.tenantId());
        assertEquals("owner-a", reloaded.ownerId());
        assertEquals("Persistent Project", reloaded.name());

        PostgresProjectOwnershipAdapter ownership = new PostgresProjectOwnershipAdapter(jdbc);
        assertTrue(ownership.isOwnerOrMember(project.id(), "owner-a"));
        assertTrue(ownership.isOwnerOrMember(project.id(), "admin-a"));
        assertFalse(ownership.isOwnerOrMember(project.id(), "viewer-a"));
        assertFalse(ownership.isOwnerOrMember(project.id(), "owner-b"));

        BusinessException crossTenant = assertThrows(BusinessException.class,
                () -> service.getProject(new GetProjectQuery(
                        "tenant-b", "owner-b", project.id())));
        assertEquals("PROJECT_NOT_FOUND", crossTenant.getCode());

        assertEquals("uuid", columnType(jdbc, "platform_projects", "id"));
        assertEquals("uuid", columnType(jdbc, "platform_project_memberships", "id"));
        assertEquals("uuid", columnType(jdbc, "platform_project_memberships", "project_id"));
        assertNotNull(jdbc.queryForObject("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'platform_projects'
                  AND constraint_name = 'fk_platform_projects_tenant'
                """, String.class));
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private static void seedTenant(JdbcTemplate jdbc, String tenantId, String name) {
        jdbc.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, tenantId, name, tenantId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static void seedUser(JdbcTemplate jdbc, String userId, String tenantId) {
        jdbc.update("""
                INSERT INTO platform_users (
                    id, tenant_id, external_id, display_name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, userId, tenantId, userId + "@example.com", userId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, ?)
                """, tenantId, userId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
