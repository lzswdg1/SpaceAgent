package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.ProjectPlanMergeBarrierApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierClaim;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierEntry;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectPlanMergeBarrierApplicationService
        implements ProjectPlanMergeBarrierApplicationApi {
    private final ProjectPlanMergeBarrierRepository barriers;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProjectPlanMergeBarrierApplicationService(
            ProjectPlanMergeBarrierRepository barriers, IdGenerator ids, TimeProvider time) {
        this.barriers = barriers;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Optional<ClaimView> claimNext(String workerId, int leaseSeconds, int maximumAttempts) {
        Instant now = time.now();
        int attempts = Math.max(1, Math.min(10, maximumAttempts));
        barriers.blockExhausted(attempts, now);
        return barriers.claimNext(text(workerId), ids.nextId(), now,
                        lease(now, leaseSeconds), attempts)
                .map(ProjectPlanMergeBarrierApplicationService::view);
    }

    @Override
    public ClaimView heartbeat(ClaimCommand command, int leaseSeconds) {
        var claim = claim(command);
        Instant now = time.now();
        Instant leaseUntil = lease(now, leaseSeconds);
        if (!barriers.heartbeat(claim, now, leaseUntil)) throw lost();
        return new ClaimView(claim.executionId(), claim.tenantId(), claim.ownerId(),
                claim.projectId(), claim.taskPlanId(), claim.applyIndex(), claim.planStepId(),
                claim.sourceMergeId(), claim.barrierRevision(),
                claim.attempt(), claim.claimOwner(), claim.claimToken(),
                claim.fencingToken(), leaseUntil);
    }

    @Override
    public void releaseKnownNoEffect(ClaimCommand command) {
        if (!barriers.releaseKnownNoEffect(claim(command), time.now())) throw lost();
    }

    @Override
    public void completeApplied(ClaimCommand command) {
        if (!barriers.completeApplied(claim(command), time.now())) throw lost();
    }

    @Override
    public void blockConflict(ClaimCommand command) {
        block(command, ProjectPlanMergeBarrierEntry.State.BLOCKED,
                "PROJECT_MERGE_TARGET_DRIFT");
    }

    @Override
    public void blockUnknown(ClaimCommand command, String safeErrorCode) {
        block(command, ProjectPlanMergeBarrierEntry.State.UNKNOWN, safe(safeErrorCode));
    }

    private void block(ClaimCommand command, ProjectPlanMergeBarrierEntry.State state, String code) {
        if (!barriers.blockClaim(claim(command), state, code, time.now())) throw lost();
    }

    private static ProjectPlanMergeBarrierClaim claim(ClaimCommand command) {
        return new ProjectPlanMergeBarrierClaim(
                command.executionId(), command.tenantId(), command.ownerId(), command.projectId(),
                command.taskPlanId(), command.applyIndex(), command.planStepId(),
                command.sourceMergeId(), command.barrierRevision(), command.attempt(),
                text(command.workerId()),
                text(command.claimToken()), command.fencingToken(), command.leaseUntil());
    }

    private static ClaimView view(ProjectPlanMergeBarrierClaim claim) {
        return new ClaimView(claim.executionId(), claim.tenantId(), claim.ownerId(),
                claim.projectId(), claim.taskPlanId(), claim.applyIndex(), claim.planStepId(),
                claim.sourceMergeId(), claim.barrierRevision(),
                claim.attempt(), claim.claimOwner(), claim.claimToken(),
                claim.fencingToken(), claim.leaseUntil());
    }

    private static Instant lease(Instant now, int seconds) {
        return now.plus(Math.max(60, Math.min(1800, seconds)), ChronoUnit.SECONDS);
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw new BusinessException("Merge barrier claim is invalid", HttpStatus.BAD_REQUEST,
                    "PROJECT_MERGE_BARRIER_CLAIM_INVALID");
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,120}")
                ? value : "PROJECT_MERGE_OUTCOME_UNKNOWN";
    }

    private static BusinessException lost() {
        return new BusinessException("Merge barrier lease was lost", HttpStatus.CONFLICT,
                "PROJECT_MERGE_BARRIER_LEASE_LOST");
    }
}
