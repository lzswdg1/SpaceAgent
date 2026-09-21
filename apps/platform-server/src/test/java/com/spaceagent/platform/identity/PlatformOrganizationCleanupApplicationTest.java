package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryOrganizationCleanupRepository;
import com.spaceagent.platform.integration.application.OrganizationCleanupCoordinator;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformOrganizationCleanupApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-23T14:00:00Z");

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private InMemoryIdentityRepository identities;
    private OrganizationCleanupApplicationService cleanup;
    private OrganizationApplicationService organizations;

    @BeforeEach
    void setUp() {
        identities = new InMemoryIdentityRepository();
        var cleanupRepository = new InMemoryOrganizationCleanupRepository();
        cleanup = new OrganizationCleanupApplicationService(
                identities, cleanupRepository, clock::get, 0, 3, 1);
        organizations = new OrganizationApplicationService(
                identities, new UuidGenerator(), clock::get, cleanup);
    }

    @Test
    void lastMemberLeaveQueuesTheCompleteOrderedStepSetIdempotently() {
        var personal = provision("cleanup-owner@example.com", "cleanup-personal");

        organizations.leaveOrganization(new LeaveOrganizationCommand(
                personal.user().id(), personal.organization().id()));

        var job = cleanup.findJob(personal.organization().id()).orElseThrow();
        assertEquals(OrganizationCleanupJobState.PENDING, job.state());
        assertEquals(NOW, job.retentionNotBefore());
        var steps = cleanup.findSteps(personal.organization().id());
        assertEquals(OrganizationCleanupStepKey.ordered(),
                steps.stream().map(OrganizationCleanupApplicationApi.OrganizationCleanupStepView::stepKey)
                        .toList());
        assertTrue(steps.stream().allMatch(step ->
                step.state() == OrganizationCleanupStepState.PENDING));

        cleanup.enqueue(personal.organization().id());
        assertEquals(OrganizationCleanupStepKey.values().length,
                cleanup.findSteps(personal.organization().id()).size());
    }

    @Test
    void retentionWindowPreventsEarlyClaims() {
        var retainedIdentities = new InMemoryIdentityRepository();
        var retainedCleanup = new OrganizationCleanupApplicationService(
                retainedIdentities, new InMemoryOrganizationCleanupRepository(),
                clock::get, 1, 3, 1);
        var retainedOrganizations = new OrganizationApplicationService(
                retainedIdentities, new UuidGenerator(), clock::get, retainedCleanup);
        var personal = retainedOrganizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "retained@example.com", "Retained", "Personal Retained", "retained-cleanup"));
        retainedOrganizations.leaveOrganization(new LeaveOrganizationCommand(
                personal.user().id(), personal.organization().id()));

        assertFalse(retainedCleanup.claimNext(
                new OrganizationCleanupApplicationApi.ClaimNextCommand("worker", 30)).isPresent());
        clock.set(NOW.plusSeconds(3600));
        assertTrue(retainedCleanup.claimNext(
                new OrganizationCleanupApplicationApi.ClaimNextCommand("worker", 30)).isPresent());
    }

    @Test
    void expiredClaimIsReclaimedWithANewFenceAndStaleMutationFails() {
        String organizationId = deletingOrganization("cleanup-reclaim@example.com", "cleanup-reclaim");
        OrganizationCleanupCoordinator coordinator = new OrganizationCleanupCoordinator(cleanup);

        var first = coordinator.claimNext("worker-a", 5).orElseThrow();
        assertEquals(1, first.claim().job().attempt());
        assertEquals(1L, first.claim().job().fencingToken());
        assertEquals(OrganizationCleanupStepKey.AUTOMATION_FREEZE_PURGE,
                first.currentStep().stepKey());
        assertFalse(coordinator.claimNext("worker-b", 5).isPresent());

        clock.set(NOW.plusSeconds(6));
        var reclaimed = coordinator.claimNext("worker-b", 5).orElseThrow();
        assertEquals(2, reclaimed.claim().job().attempt());
        assertEquals(2L, reclaimed.claim().job().fencingToken());

        BusinessException stale = assertThrows(BusinessException.class,
                () -> coordinator.recordStepCompleted(first));
        assertEquals("ORGANIZATION_CLEANUP_CLAIM_LOST", stale.getCode());

        var completed = coordinator.recordStepCompleted(reclaimed);
        assertEquals(OrganizationCleanupStepState.COMPLETED, completed.state());
        assertEquals(1, completed.attempt());
        assertEquals(completed, coordinator.recordStepCompleted(reclaimed));
    }

    @Test
    void heartbeatDeferRetryAndBoundedFailurePreserveClaimAuthority() {
        deletingOrganization("cleanup-retry@example.com", "cleanup-retry");
        OrganizationCleanupCoordinator coordinator = new OrganizationCleanupCoordinator(cleanup);
        var first = coordinator.claimNext("worker-a", 5).orElseThrow();

        clock.set(NOW.plusSeconds(4));
        var heartbeat = coordinator.heartbeat(first, 5);
        assertTrue(heartbeat.claim().job().leaseUntil().isAfter(NOW.plusSeconds(8)));

        Instant retryAt = clock.get().plusSeconds(10);
        coordinator.defer(heartbeat, retryAt, "RUNTIME_LEASE_DRAIN", "Waiting for prior lease");
        assertFalse(coordinator.claimNext("worker-b", 5).isPresent());
        clock.set(retryAt);
        var second = coordinator.claimNext("worker-b", 5).orElseThrow();
        coordinator.fail(second, "OWNER_STEP_FAILED", "Owner cleanup failed");
        clock.set(clock.get().plusSeconds(2));
        var third = coordinator.claimNext("worker-c", 5).orElseThrow();
        coordinator.fail(third, "OWNER_STEP_FAILED", "Owner cleanup failed again");
        clock.set(clock.get().plusSeconds(4));
        var fourth = coordinator.claimNext("worker-d", 5).orElseThrow();
        coordinator.fail(fourth, "OWNER_STEP_FAILED", "Owner cleanup failed finally");

        var blocked = cleanup.findJob(fourth.claim().job().organizationId()).orElseThrow();
        assertEquals(OrganizationCleanupJobState.BLOCKED, blocked.state());
        var failedStep = cleanup.findSteps(fourth.claim().job().organizationId()).get(0);
        assertEquals(4, failedStep.attempt());
        assertEquals("OWNER_STEP_FAILED", failedStep.lastErrorCode());
        assertFalse(coordinator.claimNext("worker-e", 5).isPresent());
    }

    @Test
    void completionRequiresEveryStepAndIdentityFinalization() {
        String organizationId = deletingOrganization("cleanup-final@example.com", "cleanup-final");
        var claim = cleanup.claimNext(
                new OrganizationCleanupApplicationApi.ClaimNextCommand("worker", 30))
                .orElseThrow();

        BusinessException outOfOrder = assertThrows(BusinessException.class,
                () -> cleanup.completeStep(
                        new OrganizationCleanupApplicationApi.CompleteStepCommand(
                                organizationId, OrganizationCleanupStepKey.RUNTIME_QUIESCE,
                                claim.job().leaseOwner(), claim.job().leaseToken(),
                                claim.job().fencingToken())));
        assertEquals("ORGANIZATION_CLEANUP_CLAIM_LOST", outOfOrder.getCode());

        BusinessException incomplete = assertThrows(BusinessException.class,
                () -> cleanup.complete(new OrganizationCleanupApplicationApi.CompleteCommand(
                        organizationId, claim.job().leaseOwner(), claim.job().leaseToken(),
                        claim.job().fencingToken())));
        assertEquals("ORGANIZATION_CLEANUP_STEPS_INCOMPLETE", incomplete.getCode());
    }

    private String deletingOrganization(String externalId, String slug) {
        var personal = provision(externalId, slug);
        organizations.leaveOrganization(new LeaveOrganizationCommand(
                personal.user().id(), personal.organization().id()));
        return personal.organization().id();
    }

    private com.spaceagent.platform.identity.api.ProvisionedOrganizationView provision(
            String externalId,
            String slug) {
        return organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        externalId, externalId, "Personal " + externalId, slug));
    }
}
