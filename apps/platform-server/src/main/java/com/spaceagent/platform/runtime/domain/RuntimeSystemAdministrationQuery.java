package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;

public interface RuntimeSystemAdministrationQuery {
    OverviewRow overview();
    PageRows<ResourceRow> runsByOwner(String userId, int offset, int limit);
    DeletionEvidenceRow deletionEvidence(String userId);
    record OverviewRow(long runs, long activeRuns, long completedRuns, long failedRuns,
                       long cancelledRuns, long recoveringRuns, Long unknownRuns) {
    }
    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }
    record ResourceRow(String id, String organizationId, String parentId, String displayName,
                       String state, String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long primaryCount, long secondaryCount) {
    }
    record DeletionEvidenceRow(long activeRuns, long recoveringRuns) {
    }
}
