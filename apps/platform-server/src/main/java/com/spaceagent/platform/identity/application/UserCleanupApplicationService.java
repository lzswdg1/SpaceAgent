package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.UserCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityUserAdministrationRepository;
import com.spaceagent.platform.identity.domain.UserCleanupJob;
import com.spaceagent.platform.identity.domain.UserCleanupJobState;
import com.spaceagent.platform.identity.domain.UserCleanupRepository;
import com.spaceagent.platform.identity.domain.UserCleanupStep;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.identity.domain.UserCleanupStepState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional
public class UserCleanupApplicationService implements UserCleanupApplicationApi {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private final IdentityUserAdministrationRepository users;
    private final UserCleanupRepository repository;
    private final TimeProvider time;
    private final int retentionHours;
    private final int maxAttempts;
    private final int retryBaseSeconds;

    public UserCleanupApplicationService(
            IdentityUserAdministrationRepository users,
            UserCleanupRepository repository,
            TimeProvider time,
            @Value("${platform.identity.user-cleanup.retention-hours:24}") int retentionHours,
            @Value("${platform.identity.user-cleanup.max-attempts:10}") int maxAttempts,
            @Value("${platform.identity.user-cleanup.retry-base-seconds:30}") int retryBaseSeconds) {
        this.users = users;
        this.repository = repository;
        this.time = time;
        if (retentionHours < 0 || retentionHours > 720) throw invalid("retention-hours is invalid");
        if (maxAttempts < 1 || maxAttempts > 100) throw invalid("max-attempts is invalid");
        if (retryBaseSeconds < 1 || retryBaseSeconds > 3600)
            throw invalid("retry-base-seconds is invalid");
        this.retentionHours = retentionHours;
        this.maxAttempts = maxAttempts;
        this.retryBaseSeconds = retryBaseSeconds;
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public UserCleanupJobView enqueue(EnqueueCommand command) {
        validateEnqueue(command);
        Optional<UserCleanupJob> current = repository.findJob(command.userId());
        if (current.isPresent()) {
            UserCleanupJob existing = current.orElseThrow();
            if (!existing.commandId().equals(command.commandId())
                    || !existing.reasonHash().equals(command.reasonHash())) {
                throw conflict("User cleanup already exists", "USER_CLEANUP_ALREADY_REQUESTED");
            }
            return toView(existing);
        }
        var user = users.findUser(command.userId()).orElseThrow(UserCleanupApplicationService::notFound);
        if (!"SUSPENDED".equals(user.status())) {
            throw conflict("User must be SUSPENDED before deletion", "USER_CLEANUP_NOT_SUSPENDED");
        }
        Instant now = time.now();
        if (!users.updateStatus(command.userId(), "SUSPENDED", "DELETION_PENDING", now)) {
            throw conflict("User cleanup status changed concurrently", "USER_CLEANUP_STATUS_CONFLICT");
        }
        Instant notBefore = now.plus(retentionHours, ChronoUnit.HOURS);
        UserCleanupJob job = new UserCleanupJob(command.userId(), command.commandId(),
                command.requestedBy(), command.reasonHash(), UserCleanupJobState.PENDING,
                notBefore, notBefore, 0, maxAttempts, null, null, 0, null,
                null, null, 0, now, now, null);
        List<UserCleanupStep> steps = UserCleanupStepKey.ordered().stream()
                .map(key -> new UserCleanupStep(command.userId(), key, key.sequence(),
                        UserCleanupStepState.PENDING, 0, null, null, now, now, null))
                .toList();
        repository.enqueue(job, steps);
        return toView(repository.findJob(command.userId()).orElse(job));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserCleanupJobView> findJob(String userId) {
        return repository.findJob(required(userId, "userId")).map(UserCleanupApplicationService::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserCleanupStepView> findSteps(String userId) {
        return repository.findSteps(required(userId, "userId")).stream()
                .map(UserCleanupApplicationService::toView).toList();
    }

    @Override
    public Optional<UserCleanupClaimView> claimNext(ClaimNextCommand command) {
        String owner = required(command.leaseOwner(), "leaseOwner");
        requireLease(command.leaseSeconds());
        return repository.claimNext(owner, UUID.randomUUID().toString(), command.leaseSeconds(), time.now())
                .map(job -> claim(job, repository.findSteps(job.userId())));
    }

    @Override
    public UserCleanupClaimView heartbeat(HeartbeatCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        requireLease(command.leaseSeconds());
        UserCleanupJob job = repository.heartbeat(command.userId(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), command.leaseSeconds(), time.now())
                .orElseThrow(UserCleanupApplicationService::claimLost);
        return claim(job, repository.findSteps(job.userId()));
    }

    @Override
    public UserCleanupStepView completeStep(CompleteStepCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        if (command.stepKey() == null) throw invalid("stepKey is required");
        return repository.completeStep(command.userId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), time.now())
                .map(UserCleanupApplicationService::toView)
                .orElseThrow(UserCleanupApplicationService::claimLost);
    }

    @Override
    public UserCleanupJobView defer(DeferCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        if (command.stepKey() == null || command.nextAttemptAt() == null
                || !command.nextAttemptAt().isAfter(time.now())) throw invalid("defer input is invalid");
        SafeError error = safeError(command.errorCode(), command.errorSummary());
        return repository.defer(command.userId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), command.nextAttemptAt(),
                        error.code(), error.summary(), time.now())
                .map(UserCleanupApplicationService::toView)
                .orElseThrow(UserCleanupApplicationService::claimLost);
    }

    @Override
    public UserCleanupJobView fail(FailCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        if (command.stepKey() == null) throw invalid("stepKey is required");
        SafeError error = safeError(command.errorCode(), command.errorSummary());
        UserCleanupJob current = repository.findJob(command.userId())
                .filter(UserCleanupJob::claimed).orElseThrow(UserCleanupApplicationService::claimLost);
        int exponent = Math.max(0, Math.min(10, current.attempt() - 1));
        long delay = Math.min(3600L, retryBaseSeconds * (1L << exponent));
        return repository.fail(command.userId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), time.now().plusSeconds(delay),
                        error.code(), error.summary(), time.now())
                .map(UserCleanupApplicationService::toView)
                .orElseThrow(UserCleanupApplicationService::claimLost);
    }

    @Override
    public UserCleanupJobView block(BlockCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        if (command.stepKey() == null) throw invalid("stepKey is required");
        SafeError error = safeError(command.errorCode(), command.errorSummary());
        return repository.block(command.userId(), command.stepKey(), command.leaseOwner(),
                        command.leaseToken(), command.fencingToken(), error.code(), error.summary(),
                        time.now())
                .map(UserCleanupApplicationService::toView)
                .orElseThrow(UserCleanupApplicationService::claimLost);
    }

    @Override
    public UserCleanupJobView complete(CompleteCommand command) {
        validateClaim(command.userId(), command.leaseOwner(), command.leaseToken(), command.fencingToken());
        if (repository.countIncompleteSteps(command.userId()) != 0L)
            throw conflict("User cleanup steps are incomplete", "USER_CLEANUP_STEPS_INCOMPLETE");
        var user = users.findUser(command.userId()).orElseThrow(UserCleanupApplicationService::notFound);
        if (!"DELETED".equals(user.status()))
            throw conflict("User tombstone is not finalized", "USER_CLEANUP_NOT_FINALIZED");
        return repository.complete(command.userId(), command.leaseOwner(), command.leaseToken(),
                        command.fencingToken(), time.now())
                .map(UserCleanupApplicationService::toView)
                .orElseThrow(UserCleanupApplicationService::claimLost);
    }

    private UserCleanupClaimView claim(UserCleanupJob job, List<UserCleanupStep> steps) {
        return new UserCleanupClaimView(toView(job), steps.stream()
                .map(UserCleanupApplicationService::toView).toList());
    }

    private static UserCleanupJobView toView(UserCleanupJob job) {
        return new UserCleanupJobView(job.userId(), job.commandId(), job.requestedBy(), job.state(),
                job.retentionNotBefore(), job.nextAttemptAt(), job.attempt(), job.maxAttempts(),
                job.leaseOwner(), job.leaseToken(), job.fencingToken(), job.leaseUntil(),
                job.lastErrorCode(), job.lastErrorSummary(), job.revision(), job.createdAt(),
                job.updatedAt(), job.completedAt());
    }

    private static UserCleanupStepView toView(UserCleanupStep step) {
        return new UserCleanupStepView(step.userId(), step.stepKey(), step.sequence(), step.state(),
                step.attempt(), step.lastErrorCode(), step.lastErrorSummary(), step.createdAt(),
                step.updatedAt(), step.completedAt());
    }

    private static void validateEnqueue(EnqueueCommand command) {
        if (command == null || command.commandId() == null || command.requestedBy() == null)
            throw invalid("User cleanup command is invalid");
        required(command.userId(), "userId");
        if (command.reasonHash() == null || !HASH.matcher(command.reasonHash()).matches())
            throw invalid("reasonHash is invalid");
    }

    private static void validateClaim(String userId, String owner, String token, long fence) {
        required(userId, "userId"); required(owner, "leaseOwner"); required(token, "leaseToken");
        if (fence < 1L) throw invalid("fencingToken must be positive");
    }

    private static void requireLease(int seconds) {
        if (seconds < 5 || seconds > 300) throw invalid("leaseSeconds must be between 5 and 300");
    }

    private static SafeError safeError(String code, String summary) {
        String safeCode = required(code, "errorCode");
        String safeSummary = required(summary, "errorSummary");
        if (!SAFE_CODE.matcher(safeCode).matches() || safeSummary.length() > 500)
            throw invalid("Safe error evidence is invalid");
        return new SafeError(safeCode, safeSummary);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + " is required");
        return value.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "USER_CLEANUP_INVALID");
    }

    private static BusinessException notFound() {
        return new BusinessException("User not found", HttpStatus.NOT_FOUND, "SYSTEM_ADMIN_USER_NOT_FOUND");
    }

    private static BusinessException claimLost() {
        return conflict("User cleanup claim is stale or expired", "USER_CLEANUP_CLAIM_LOST");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record SafeError(String code, String summary) {
    }
}
