package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi.OrganizationCleanupClaimView;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi.OrganizationCleanupStepView;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PR2B claim/recovery coordinator skeleton.
 *
 * <p>It deliberately has no scheduler and invokes no purge participant. PR2C will bind
 * the current step to its owning module's public cleanup Application API.
 */
@Service
public class OrganizationCleanupCoordinator {

    private final OrganizationCleanupApplicationApi cleanup;

    public OrganizationCleanupCoordinator(OrganizationCleanupApplicationApi cleanup) {
        this.cleanup = cleanup;
    }

    public Optional<CleanupWorkItem> claimNext(String workerId, int leaseSeconds) {
        return cleanup.claimNext(new OrganizationCleanupApplicationApi.ClaimNextCommand(
                        workerId, leaseSeconds))
                .map(this::workItem);
    }

    public CleanupWorkItem heartbeat(CleanupWorkItem item, int leaseSeconds) {
        OrganizationCleanupClaimView claim = cleanup.heartbeat(
                new OrganizationCleanupApplicationApi.HeartbeatCommand(
                        item.claim().job().organizationId(), item.claim().job().leaseOwner(),
                        item.claim().job().leaseToken(), item.claim().job().fencingToken(),
                        leaseSeconds));
        return workItem(claim);
    }

    public OrganizationCleanupStepView recordStepCompleted(CleanupWorkItem item) {
        if (item.currentStep() == null) {
            throw new IllegalStateException("Cleanup work item has no pending owner step");
        }
        return cleanup.completeStep(new OrganizationCleanupApplicationApi.CompleteStepCommand(
                item.claim().job().organizationId(), item.currentStep().stepKey(),
                item.claim().job().leaseOwner(), item.claim().job().leaseToken(),
                item.claim().job().fencingToken()));
    }

    public void defer(
            CleanupWorkItem item,
            Instant nextAttemptAt,
            String safeCode,
            String safeSummary) {
        cleanup.defer(new OrganizationCleanupApplicationApi.DeferCommand(
                item.claim().job().organizationId(), requireCurrentStep(item).stepKey(),
                item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(),
                nextAttemptAt, safeCode, safeSummary));
    }

    public void fail(CleanupWorkItem item, String safeCode, String safeSummary) {
        cleanup.fail(new OrganizationCleanupApplicationApi.FailCommand(
                item.claim().job().organizationId(), requireCurrentStep(item).stepKey(),
                item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(),
                safeCode, safeSummary));
    }

    public void block(CleanupWorkItem item,String safeCode,String safeSummary){
        cleanup.block(new OrganizationCleanupApplicationApi.BlockCommand(
                item.claim().job().organizationId(),requireCurrentStep(item).stepKey(),
                item.claim().job().leaseOwner(),item.claim().job().leaseToken(),
                item.claim().job().fencingToken(),safeCode,safeSummary));
    }

    private CleanupWorkItem workItem(OrganizationCleanupClaimView claim) {
        List<OrganizationCleanupStepKey> actual = claim.steps().stream()
                .map(OrganizationCleanupStepView::stepKey)
                .toList();
        if (!actual.equals(OrganizationCleanupStepKey.ordered())) {
            throw new IllegalStateException("Cleanup job does not declare the ADR-025 step set");
        }
        OrganizationCleanupStepView current = claim.steps().stream()
                .filter(step -> step.state() == OrganizationCleanupStepState.PENDING)
                .findFirst()
                .orElse(null);
        return new CleanupWorkItem(claim, current);
    }

    private static OrganizationCleanupStepView requireCurrentStep(CleanupWorkItem item) {
        if (item.currentStep() == null) {
            throw new IllegalStateException("Cleanup work item has no pending owner step");
        }
        return item.currentStep();
    }

    public record CleanupWorkItem(
            OrganizationCleanupClaimView claim,
            OrganizationCleanupStepView currentStep) {
    }
}
