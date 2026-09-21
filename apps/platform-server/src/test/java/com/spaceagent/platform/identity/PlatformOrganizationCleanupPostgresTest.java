package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationCleanupRepository;
import com.spaceagent.platform.integration.application.OrganizationCleanupCoordinator;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PlatformOrganizationCleanupPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_organization_cleanup")
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

    @BeforeEach
    void clearCleanupControlPlane() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        jdbc.update("DELETE FROM platform_organization_cleanup_steps");
        jdbc.update("DELETE FROM platform_organization_cleanup_jobs");
    }

    @Test
    void lastMemberLeaveAndJobEnqueueRollBackAtomically() {
        TestContext context = context("atomic-cleanup@example.com", "atomic-cleanup", 10);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(context.dataSource()));

        assertThrows(RollbackSignal.class, () -> transactions.executeWithoutResult(status -> {
            context.organizations().leaveOrganization(new LeaveOrganizationCommand(
                    context.userId(), context.organizationId()));
            throw new RollbackSignal();
        }));

        assertEquals(TenantStatus.ACTIVE,
                context.identities().findTenantById(context.organizationId()).orElseThrow().status());
        assertTrue(context.identities().findMembership(
                context.organizationId(), context.userId()).orElseThrow().isActive());
        assertFalse(context.cleanup().findJob(context.organizationId()).isPresent());

        context.organizations().leaveOrganization(new LeaveOrganizationCommand(
                context.userId(), context.organizationId()));
        assertEquals(TenantStatus.DELETING,
                context.identities().findTenantById(context.organizationId()).orElseThrow().status());
        assertEquals(OrganizationCleanupJobState.PENDING,
                context.cleanup().findJob(context.organizationId()).orElseThrow().state());
        assertEquals(OrganizationCleanupStepKey.values().length,
                context.cleanup().findSteps(context.organizationId()).size());
        assertEquals(110L, context.jdbc().queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
    }

    @Test
    void skipLockedLeaseRecoveryAndFencingHaveOneAuthoritativeClaimer() throws Exception {
        TestContext context = context("claim-cleanup@example.com", "claim-cleanup", 3);
        context.organizations().leaveOrganization(new LeaveOrganizationCommand(
                context.userId(), context.organizationId()));
        OrganizationCleanupCoordinator firstCoordinator =
                new OrganizationCleanupCoordinator(context.cleanup());
        OrganizationCleanupApplicationService secondService = new OrganizationCleanupApplicationService(
                context.identities(), new PostgresOrganizationCleanupRepository(context.jdbc()),
                context.time(), 0, 3, 1);
        OrganizationCleanupCoordinator secondCoordinator =
                new OrganizationCleanupCoordinator(secondService);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<OrganizationCleanupCoordinator.CleanupWorkItem>> firstClaim = () -> {
            start.await(5, TimeUnit.SECONDS);
            return firstCoordinator.claimNext("worker-a", 30);
        };
        Callable<Optional<OrganizationCleanupCoordinator.CleanupWorkItem>> secondClaim = () -> {
            start.await(5, TimeUnit.SECONDS);
            return secondCoordinator.claimNext("worker-b", 30);
        };
        var executor = Executors.newFixedThreadPool(2);
        List<Optional<OrganizationCleanupCoordinator.CleanupWorkItem>> claims;
        try {
            var one = executor.submit(firstClaim);
            var two = executor.submit(secondClaim);
            start.countDown();
            claims = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, claims.stream().filter(Optional::isPresent).count());
        var original = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertEquals(1L, original.claim().job().fencingToken());

        context.jdbc().update("""
                UPDATE platform_organization_cleanup_jobs
                SET lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE organization_id = ?
                """, context.organizationId());
        var reclaimed = secondCoordinator.claimNext("worker-c", 30).orElseThrow();
        assertEquals(2L, reclaimed.claim().job().fencingToken());
        assertEquals(2, reclaimed.claim().job().attempt());

        BusinessException stale = assertThrows(BusinessException.class,
                () -> firstCoordinator.recordStepCompleted(original));
        assertEquals("ORGANIZATION_CLEANUP_CLAIM_LOST", stale.getCode());
        var completed = secondCoordinator.recordStepCompleted(reclaimed);
        assertEquals(OrganizationCleanupStepState.COMPLETED, completed.state());
        assertEquals(completed, secondCoordinator.recordStepCompleted(reclaimed));

        var nextStep = secondCoordinator.heartbeat(reclaimed, 30);
        Instant retryAt = Instant.now().plusSeconds(60);
        secondCoordinator.defer(
                nextStep, retryAt, "RUNTIME_LEASE_DRAIN", "Waiting for prior Runtime lease");
        assertFalse(secondCoordinator.claimNext("worker-d", 30).isPresent());
        context.jdbc().update("""
                UPDATE platform_organization_cleanup_jobs
                SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                WHERE organization_id = ?
                """, context.organizationId());
        var third = secondCoordinator.claimNext("worker-d", 30).orElseThrow();
        secondCoordinator.fail(third, "OWNER_STEP_FAILED", "Owner step failed safely");
        context.jdbc().update("""
                UPDATE platform_organization_cleanup_jobs
                SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                WHERE organization_id = ?
                """, context.organizationId());
        var fourth = secondCoordinator.claimNext("worker-e", 30).orElseThrow();
        secondCoordinator.fail(fourth, "OWNER_STEP_FAILED", "Owner step failed finally");
        assertEquals(OrganizationCleanupJobState.BLOCKED,
                secondService.findJob(context.organizationId()).orElseThrow().state());
        var runtimeStep = secondService.findSteps(context.organizationId()).stream()
                .filter(step -> step.stepKey() == OrganizationCleanupStepKey.RUNTIME_QUIESCE)
                .findFirst().orElseThrow();
        assertEquals(3, runtimeStep.attempt());
        assertEquals("OWNER_STEP_FAILED", runtimeStep.lastErrorCode());
        assertFalse(secondCoordinator.claimNext("worker-f", 30).isPresent());
    }

    private static TestContext context(String externalId, String slug, int maxAttempts) {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PostgresIdentityRepository identities = new PostgresIdentityRepository(jdbc);
        TimeProvider time = () -> Instant.now().minusSeconds(1);
        var cleanup = new OrganizationCleanupApplicationService(
                identities, new PostgresOrganizationCleanupRepository(jdbc),
                time, 0, maxAttempts, 1);
        var organizations = new OrganizationApplicationService(
                identities, new UuidGenerator(), time, cleanup);
        var personal = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        externalId, externalId, "Personal " + externalId, slug));
        return new TestContext(
                dataSource, jdbc, identities, cleanup, organizations, time,
                personal.organization().id(), personal.user().id());
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record TestContext(
            DriverManagerDataSource dataSource,
            JdbcTemplate jdbc,
            PostgresIdentityRepository identities,
            OrganizationCleanupApplicationService cleanup,
            OrganizationApplicationService organizations,
            TimeProvider time,
            String organizationId,
            String userId) {
    }

    private static final class RollbackSignal extends RuntimeException {
    }
}
