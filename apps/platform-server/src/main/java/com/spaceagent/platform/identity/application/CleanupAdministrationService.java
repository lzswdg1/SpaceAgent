package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.CleanupAdministrationApi;
import com.spaceagent.platform.identity.domain.IdentityCleanupAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class CleanupAdministrationService implements CleanupAdministrationApi {
    private final IdentityCleanupAdministrationQuery query;
    private final TimeProvider time;

    public CleanupAdministrationService(IdentityCleanupAdministrationQuery query, TimeProvider time) {
        this.query = query;
        this.time = time;
    }

    @Override
    public CleanupOverview overview() {
        var value = query.overview();
        return new CleanupOverview(value.pending(), value.claimed(), value.retry(), value.blocked(),
                value.completed(), value.blockers().stream()
                .map(row -> new BlockerCount(row.code(), row.count())).toList());
    }

    @Override
    public SystemAdministrationPage<CleanupJobSummary> jobs(
            int page, int pageSize, String kind, String state, String text) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.jobs(safePage * safeSize, safeSize, kind(kind, false), state(state),
                bounded(text, 120));
        return new SystemAdministrationPage<>(rows.items().stream().map(this::job).toList(),
                safePage, safeSize, rows.total(), time.now());
    }

    @Override
    public CleanupJobDetail job(String kind, String subjectId) {
        String safeKind = kind(kind, true);
        String safeSubject = bounded(subjectId, 36);
        if (safeSubject == null) throw notFound();
        var row = query.job(safeKind, safeSubject).orElseThrow(CleanupAdministrationService::notFound);
        return new CleanupJobDetail(job(row), query.steps(safeKind, safeSubject).stream().map(value ->
                new CleanupStepSummary(value.stepKey(), value.sequence(), value.state(),
                        value.attempt(), value.lastErrorCode(), value.lastErrorSummary(),
                        value.createdAt(), value.updatedAt(), value.completedAt())).toList());
    }

    private CleanupJobSummary job(IdentityCleanupAdministrationQuery.JobRow value) {
        return new CleanupJobSummary(value.kind(), value.subjectId(), value.state(), value.commandId(),
                value.requestedBy(), value.retentionNotBefore(), value.nextAttemptAt(), value.attempt(),
                value.maxAttempts(), value.lastErrorCode(), value.lastErrorSummary(), value.revision(),
                value.completedSteps(), value.totalSteps(), value.currentStepKey(), value.createdAt(),
                value.updatedAt(), value.completedAt());
    }

    private static String kind(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) return null;
            throw notFound();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("USER", "ORGANIZATION").contains(normalized)) {
            throw new BusinessException("Cleanup kind is invalid", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_CLEANUP_KIND_INVALID");
        }
        return normalized;
    }

    private static String state(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return java.util.Set.of("PENDING", "CLAIMED", "RETRY", "BLOCKED", "COMPLETED")
                .contains(normalized) ? normalized : null;
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maximum, trimmed.length()));
    }

    private static BusinessException notFound() {
        return new BusinessException("Cleanup job not found", HttpStatus.NOT_FOUND,
                "SYSTEM_ADMIN_CLEANUP_NOT_FOUND");
    }
}
