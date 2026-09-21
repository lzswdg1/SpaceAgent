package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.AddOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.api.TransferOrganizationOwnershipCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationCleanupRepository;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class PlatformOrganizationPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T13:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_organization_lifecycle")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
    }

    @Test
    void migrationEnforcesCreatorOwnerTransferAndDeletingLifecycle() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        PostgresIdentityRepository repository = new PostgresIdentityRepository(jdbc);
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        var cleanup = new OrganizationCleanupApplicationService(
                repository, new PostgresOrganizationCleanupRepository(jdbc), time, 0, 10, 1);
        OrganizationApplicationService organizations = new OrganizationApplicationService(
                repository, ids, time, cleanup);
        IdentityApplicationService identity = new IdentityApplicationService(repository, ids, time);
        var alice = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "alice@example.com", "Alice", "Personal Alice", "personal-alice"));
        var bob = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "bob@example.com", "Bob", "Personal Bob", "personal-bob"));
        String sharedId = organizations.createOrganization(new CreateOrganizationCommand(
                alice.user().id(), "Shared", "shared-postgres")).id();
        organizations.addMember(new AddOrganizationMemberCommand(
                alice.user().id(), sharedId, bob.user().id(), TenantRole.MEMBER));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveMembership(new TenantMembership(
                        sharedId, bob.user().id(), TenantRole.OWNER,
                        TenantMembershipStatus.ACTIVE, NOW, NOW)));

        var transferred = organizations.transferOwnership(
                new TransferOrganizationOwnershipCommand(
                        alice.user().id(), sharedId, bob.user().id()));
        assertEquals(bob.user().id(), transferred.creatorUserId());
        assertEquals(TenantRole.ADMIN,
                repository.findMembership(sharedId, alice.user().id()).orElseThrow().role());
        assertEquals(TenantRole.OWNER,
                repository.findMembership(sharedId, bob.user().id()).orElseThrow().role());

        String soloId = organizations.createOrganization(new CreateOrganizationCommand(
                alice.user().id(), "Disposable", "disposable-postgres")).id();
        var left = organizations.leaveOrganization(new LeaveOrganizationCommand(
                alice.user().id(), soloId));
        assertEquals(TenantStatus.DELETING, left.organizationStatus());
        assertFalse(identity.isMemberOfTenant(soloId, alice.user().id()));
        assertNotNull(jdbc.queryForObject("""
                SELECT deletion_requested_at
                FROM platform_tenants
                WHERE id = ?
                """, java.sql.Timestamp.class, soloId));

        assertEquals("character varying", columnType(jdbc, "platform_tenants", "creator_user_id"));
        assertNotNull(jdbc.queryForObject("""
                SELECT indexname
                FROM pg_indexes
                WHERE tablename = 'platform_tenant_memberships'
                  AND indexname = 'uk_platform_tenant_single_active_owner'
                """, String.class));
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
