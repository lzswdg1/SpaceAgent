package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJob;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupRepository;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStep;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class OrganizationCleanupApplicationService implements OrganizationCleanupApplicationApi {

    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private final IdentityRepository identities;
    private final OrganizationCleanupRepository repository;
    private final TimeProvider time;
    private final int retentionHours;
    private final int maxAttempts;
    private final int retryBaseSeconds;

    public OrganizationCleanupApplicationService(
            IdentityRepository identities,
            OrganizationCleanupRepository repository,
            TimeProvider time,
            @Value("${platform.identity.cleanup.retention-hours:24}") int retentionHours,
            @Value("${platform.identity.cleanup.max-attempts:10}") int maxAttempts,
            @Value("${platform.identity.cleanup.retry-base-seconds:30}") int retryBaseSeconds) {
        this.identities = identities;
        this.repository = repository;
        this.time = time;
        if (retentionHours < 0 || retentionHours > 720) {
            throw new IllegalArgumentException("Cleanup retention-hours must be between 0 and 720");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("Cleanup max-attempts must be between 1 and 100");
        }
        if (retryBaseSeconds < 1 || retryBaseSeconds > 3600) {
            throw new IllegalArgumentException("Cleanup retry-base-seconds must be between 1 and 3600");
        }
        this.retentionHours = retentionHours;
        this.maxAttempts = maxAttempts;
        this.retryBaseSeconds = retryBaseSeconds;
    }

    @Override
    public void resumeStorageDeletionAfterAdminApproval(String organizationId) {
        repository.resumeStorageDeletionAfterAdminApproval(requireText(organizationId, "organizationId"), time.now());
    }

    @Override
    public OrganizationCleanupJobView enqueue(String organizationId) {
        String id = requireText(organizationId, "organizationId");
        var organization = identities.findTenantByIdForUpdate(id)
                .orElseThrow(OrganizationCleanupApplicationService::notFound);
        if (organization.status() != TenantStatus.DELETING) {
            throw conflict(
                    "Only a DELETING Organization can be queued for cleanup",
                    "ORGANIZATION_CLEANUP_NOT_DELETING");
        }
        Optional<OrganizationCleanupJob> current = repository.findJob(id);
        if (current.isPresent()) {
            return toView(current.orElseThrow());
        }
        Instant now = time.now();
        Instant notBefore = now.plus(retentionHours, ChronoUnit.HOURS);
        OrganizationCleanupJob job = new OrganizationCleanupJob(
                id, OrganizationCleanupJobState.PENDING, notBefore, notBefore,
                0, maxAttempts, null, null, 0, null, null, null,
                0, now, now, null);
        List<OrganizationCleanupStep> steps = OrganizationCleanupStepKey.ordered().stream()
                .map(key -> new OrganizationCleanupStep(
                        id, key, key.sequence(), OrganizationCleanupStepState.PENDING,
                        0, null, null, now, now, null))
                .toList();
        repository.enqueue(job, steps);
        return toView(repository.findJob(id).orElse(job));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationCleanupJobView> findJob(String organizationId) {
        return repository.findJob(requireText(organizationId, "organizationId"))
                .map(OrganizationCleanupApplicationService::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationCleanupStepView> findSteps(String organizationId) {
        return repository.findSteps(requireText(organizationId, "organizationId")).stream()
                .map(OrganizationCleanupApplicationService::toView)
                .toList();
    }

    @Override
    public Optional<OrganizationCleanupClaimView> claimNext(ClaimNextCommand command) {
        String owner = requireText(command.leaseOwner(), "leaseOwner");
        requireLeaseSeconds(command.leaseSeconds());
        return repository.claimNext(
                        owner, UUID.randomUUID().toString(), command.leaseSeconds(), time.now())
                .map(job -> claimView(job, repository.findSteps(job.organizationId())));
    }

    @Override
    public OrganizationCleanupClaimView heartbeat(HeartbeatCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken());
        requireLeaseSeconds(command.leaseSeconds());
        OrganizationCleanupJob job = repository.heartbeat(
                        command.organizationId(), command.leaseOwner(), command.leaseToken(),
                        command.fencingToken(), command.leaseSeconds(), time.now())
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
        return claimView(job, repository.findSteps(job.organizationId()));
    }

    @Override
    public OrganizationCleanupStepView completeStep(CompleteStepCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken());
        if (command.stepKey() == null) {
            throw invalid("stepKey is required");
        }
        return repository.completeStep(
                        command.organizationId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), time.now())
                .map(OrganizationCleanupApplicationService::toView)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
    }

    @Override
    public OrganizationCleanupJobView defer(DeferCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken());
        requireStepKey(command.stepKey());
        Instant now = time.now();
        if (command.nextAttemptAt() == null || !command.nextAttemptAt().isAfter(now)) {
            throw invalid("nextAttemptAt must be in the future");
        }
        SafeError error = safeError(command.errorCode(), command.errorSummary());
        return repository.defer(
                        command.organizationId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(),
                        command.fencingToken(), command.nextAttemptAt(), error.code(),
                        error.summary(), now)
                .map(OrganizationCleanupApplicationService::toView)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
    }

    @Override
    public OrganizationCleanupJobView fail(FailCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken());
        requireStepKey(command.stepKey());
        Instant now = time.now();
        SafeError error = safeError(command.errorCode(), command.errorSummary());
        OrganizationCleanupJob current = repository.findJob(command.organizationId())
                .filter(OrganizationCleanupJob::claimed)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
        int exponent = Math.max(0, Math.min(10, current.attempt() - 1));
        long delay = Math.min(3600L, retryBaseSeconds * (1L << exponent));
        return repository.fail(
                        command.organizationId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(),
                        command.fencingToken(), now.plusSeconds(delay), error.code(),
                        error.summary(), now)
                .map(OrganizationCleanupApplicationService::toView)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
    }

    @Override
    public OrganizationCleanupJobView block(BlockCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        requireStepKey(command.stepKey());SafeError error=safeError(command.errorCode(),command.errorSummary());
        return repository.block(command.organizationId(),command.stepKey(),command.leaseOwner(),command.leaseToken(),
                command.fencingToken(),error.code(),error.summary(),time.now())
                .map(OrganizationCleanupApplicationService::toView)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
    }

    @Override
    public OrganizationCleanupJobView complete(CompleteCommand command) {
        validateClaim(command.organizationId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken());
        if (repository.countIncompleteSteps(command.organizationId()) != 0L) {
            throw conflict(
                    "Cleanup has incomplete owner steps",
                    "ORGANIZATION_CLEANUP_STEPS_INCOMPLETE");
        }
        var organization = identities.findTenantByIdForUpdate(command.organizationId())
                .orElseThrow(OrganizationCleanupApplicationService::notFound);
        if (organization.status() != TenantStatus.DELETED) {
            throw conflict(
                    "Identity finalization has not marked the Organization DELETED",
                    "ORGANIZATION_CLEANUP_NOT_FINALIZED");
        }
        return repository.complete(
                        command.organizationId(), command.leaseOwner(), command.leaseToken(),
                        command.fencingToken(), time.now())
                .map(OrganizationCleanupApplicationService::toView)
                .orElseThrow(OrganizationCleanupApplicationService::claimLost);
    }

    private OrganizationCleanupClaimView claimView(
            OrganizationCleanupJob job,
            List<OrganizationCleanupStep> steps) {
        return new OrganizationCleanupClaimView(
                toView(job), steps.stream().map(OrganizationCleanupApplicationService::toView)
                        .toList());
    }

    private static OrganizationCleanupJobView toView(OrganizationCleanupJob job) {
        return new OrganizationCleanupJobView(
                job.organizationId(), job.state(), job.retentionNotBefore(), job.nextAttemptAt(),
                job.attempt(), job.maxAttempts(), job.leaseOwner(), job.leaseToken(),
                job.fencingToken(), job.leaseUntil(), job.lastErrorCode(),
                job.lastErrorSummary(), job.revision(), job.createdAt(), job.updatedAt(),
                job.completedAt());
    }

    private static OrganizationCleanupStepView toView(OrganizationCleanupStep step) {
        return new OrganizationCleanupStepView(
                step.organizationId(), step.stepKey(), step.sequence(), step.state(),
                step.attempt(), step.lastErrorCode(), step.lastErrorSummary(), step.createdAt(),
                step.updatedAt(), step.completedAt());
    }

    private static void validateClaim(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {
        requireText(organizationId, "organizationId");
        requireText(leaseOwner, "leaseOwner");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1L) {
            throw invalid("fencingToken must be positive");
        }
    }

    private static void requireLeaseSeconds(int seconds) {
        if (seconds < 5 || seconds > 300) {
            throw invalid("leaseSeconds must be between 5 and 300");
        }
    }

    private static void requireStepKey(OrganizationCleanupStepKey stepKey) {
        if (stepKey == null) {
            throw invalid("stepKey is required");
        }
    }

    private static SafeError safeError(String code, String summary) {
        String safeCode = requireText(code, "errorCode");
        if (!SAFE_CODE.matcher(safeCode).matches()) {
            throw invalid("errorCode must be an uppercase safe code");
        }
        String safeSummary = requireText(summary, "errorSummary");
        if (safeSummary.length() > 500) {
            throw invalid("errorSummary must not exceed 500 characters");
        }
        return new SafeError(safeCode, safeSummary);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "Organization not found", HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND");
    }

    private static BusinessException claimLost() {
        return conflict("Cleanup claim is stale or expired", "ORGANIZATION_CLEANUP_CLAIM_LOST");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record SafeError(String code, String summary) {
    }
}
