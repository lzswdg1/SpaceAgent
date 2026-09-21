package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;

import java.time.Instant;
import java.util.List;

public interface CleanupAdministrationApi {
    CleanupOverview overview();

    SystemAdministrationPage<CleanupJobSummary> jobs(
            int page, int pageSize, String kind, String state, String query);

    CleanupJobDetail job(String kind, String subjectId);

    record CleanupJobSummary(String kind, String subjectId, String state, String commandId,
                             String requestedBy, Instant retentionNotBefore, Instant nextAttemptAt,
                             int attempt, int maxAttempts, String lastErrorCode,
                             String lastErrorSummary, long revision, long completedSteps,
                             long totalSteps, String currentStepKey, Instant createdAt,
                             Instant updatedAt, Instant completedAt) {
    }

    record CleanupStepSummary(String stepKey, int sequence, String state, int attempt,
                              String lastErrorCode, String lastErrorSummary, Instant createdAt,
                              Instant updatedAt, Instant completedAt) {
    }

    record CleanupJobDetail(CleanupJobSummary job, List<CleanupStepSummary> steps) {
        public CleanupJobDetail { steps = steps == null ? List.of() : List.copyOf(steps); }
    }

    record CleanupOverview(long pending, long claimed, long retry, long blocked, long completed,
                           List<BlockerCount> blockers) {
        public CleanupOverview { blockers = blockers == null ? List.of() : List.copyOf(blockers); }
    }

    record BlockerCount(String code, long count) {
    }
}
