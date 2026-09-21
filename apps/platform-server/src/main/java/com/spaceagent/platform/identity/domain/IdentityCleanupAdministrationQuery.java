package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdentityCleanupAdministrationQuery {
    OverviewRow overview();

    PageRows<JobRow> jobs(int offset, int limit, String kind, String state, String query);

    Optional<JobRow> job(String kind, String subjectId);

    List<StepRow> steps(String kind, String subjectId);

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record JobRow(String kind, String subjectId, String state, String commandId,
                  String requestedBy, Instant retentionNotBefore, Instant nextAttemptAt,
                  int attempt, int maxAttempts, String lastErrorCode, String lastErrorSummary,
                  long revision, long completedSteps, long totalSteps, String currentStepKey,
                  Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    record StepRow(String stepKey, int sequence, String state, int attempt,
                   String lastErrorCode, String lastErrorSummary, Instant createdAt,
                   Instant updatedAt, Instant completedAt) {
    }

    record OverviewRow(long pending, long claimed, long retry, long blocked, long completed,
                       List<BlockerRow> blockers) {
        public OverviewRow { blockers = blockers == null ? List.of() : List.copyOf(blockers); }
    }

    record BlockerRow(String code, long count) {
    }
}
