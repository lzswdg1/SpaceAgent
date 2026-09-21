package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.AcceptOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationInvitationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationInvitationRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationCleanupRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PlatformOrganizationInvitationPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_organization_invitations")
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
    void migrationStoresOnlyDigestAndAtomicAcceptanceHasOneWinner() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        PostgresIdentityRepository identities = new PostgresIdentityRepository(jdbc);
        PostgresOrganizationInvitationRepository repository =
                new PostgresOrganizationInvitationRepository(jdbc);
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        var cleanup = new OrganizationCleanupApplicationService(
                identities, new PostgresOrganizationCleanupRepository(jdbc), time, 0, 10, 1);
        OrganizationApplicationService organizations = new OrganizationApplicationService(
                identities, ids, time, cleanup);
        OrganizationInvitationApplicationService invitations =
                new OrganizationInvitationApplicationService(
                        identities, repository, ids, time, 168L);
        var owner = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "pg-owner@example.com", "Owner", "Owner Personal", "pg-invite-owner"));
        var invitee = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "pg-invitee@example.com", "Invitee", "Invitee Personal", "pg-invitee"));
        String organizationId = organizations.createOrganization(new CreateOrganizationCommand(
                owner.user().id(), "Postgres Invitations", "pg-invitations")).id();
        var created = invitations.createInvitation(new CreateOrganizationInvitationCommand(
                organizationId, owner.user().id(), "pg-invitee@example.com",
                TenantRole.MEMBER, 24L));

        String storedHash = jdbc.queryForObject("""
                SELECT token_hash
                FROM platform_organization_invitations
                WHERE id = ?
                """, String.class, created.invitation().id());
        assertNotNull(storedHash);
        assertNotEquals(created.token(), storedHash);
        assertEquals(64, storedHash.length());

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> accept = () -> {
            start.await(5, TimeUnit.SECONDS);
            try {
                invitations.acceptInvitation(new AcceptOrganizationInvitationCommand(
                        created.token(), invitee.user().id()));
                return true;
            } catch (BusinessException exception) {
                return false;
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Boolean>> results;
        try {
            var first = executor.submit(accept);
            var second = executor.submit(accept);
            start.countDown();
            results = List.of(first, second);
            assertEquals(1L, results.stream().filter(result -> {
                try {
                    return result.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(OrganizationInvitationStatus.ACCEPTED,
                repository.findByTokenHash(storedHash).orElseThrow().status());
        assertTrue(identities.findMembership(organizationId, invitee.user().id())
                .orElseThrow().isActive());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*)
                FROM platform_organization_invitations
                WHERE id = ? AND status = 'ACCEPTED' AND accepted_by_user_id = ?
                """, Long.class, created.invitation().id(), invitee.user().id()));
        assertEquals(110L, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
        assertNotNull(jdbc.queryForObject("""
                SELECT indexname
                FROM pg_indexes
                WHERE tablename = 'platform_organization_invitations'
                  AND indexname = 'uk_platform_organization_invitation_pending_email'
                """, String.class));
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
