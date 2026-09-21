package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.identity.api.UserCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.identity.domain.UserCleanupStepState;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class UserCleanupCoordinator {
    private final UserCleanupApplicationApi cleanup;

    public UserCleanupCoordinator(UserCleanupApplicationApi cleanup) {
        this.cleanup = cleanup;
    }

    public Optional<CleanupWorkItem> claimNext(String workerId, int leaseSeconds) {
        return cleanup.claimNext(new UserCleanupApplicationApi.ClaimNextCommand(workerId, leaseSeconds))
                .map(this::workItem);
    }

    public CleanupWorkItem heartbeat(CleanupWorkItem item, int leaseSeconds) {
        return workItem(cleanup.heartbeat(new UserCleanupApplicationApi.HeartbeatCommand(
                item.claim().job().userId(), item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(), leaseSeconds)));
    }

    public void recordStepCompleted(CleanupWorkItem item) {
        cleanup.completeStep(new UserCleanupApplicationApi.CompleteStepCommand(
                item.claim().job().userId(), requireStep(item).stepKey(),
                item.claim().job().leaseOwner(), item.claim().job().leaseToken(),
                item.claim().job().fencingToken()));
    }

    public void defer(CleanupWorkItem item, Instant retryAt, String code, String summary) {
        cleanup.defer(new UserCleanupApplicationApi.DeferCommand(item.claim().job().userId(),
                requireStep(item).stepKey(), item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(), retryAt,
                code, summary));
    }

    public void fail(CleanupWorkItem item, String code, String summary) {
        cleanup.fail(new UserCleanupApplicationApi.FailCommand(item.claim().job().userId(),
                requireStep(item).stepKey(), item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(), code, summary));
    }

    public void block(CleanupWorkItem item, String code, String summary) {
        cleanup.block(new UserCleanupApplicationApi.BlockCommand(item.claim().job().userId(),
                requireStep(item).stepKey(), item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken(), code, summary));
    }

    public void complete(CleanupWorkItem item) {
        cleanup.complete(new UserCleanupApplicationApi.CompleteCommand(item.claim().job().userId(),
                item.claim().job().leaseOwner(), item.claim().job().leaseToken(),
                item.claim().job().fencingToken()));
    }

    private CleanupWorkItem workItem(UserCleanupApplicationApi.UserCleanupClaimView claim) {
        List<UserCleanupStepKey> actual = claim.steps().stream()
                .map(UserCleanupApplicationApi.UserCleanupStepView::stepKey).toList();
        if (!actual.equals(UserCleanupStepKey.ordered())) {
            throw new IllegalStateException("User cleanup step set does not match M40-PR5");
        }
        var current = claim.steps().stream()
                .filter(step -> step.state() == UserCleanupStepState.PENDING)
                .findFirst().orElse(null);
        return new CleanupWorkItem(claim, current);
    }

    private static UserCleanupApplicationApi.UserCleanupStepView requireStep(CleanupWorkItem item) {
        if (item.currentStep() == null) throw new IllegalStateException("User cleanup has no pending step");
        return item.currentStep();
    }

    public record CleanupWorkItem(UserCleanupApplicationApi.UserCleanupClaimView claim,
                                  UserCleanupApplicationApi.UserCleanupStepView currentStep) {
    }
}
