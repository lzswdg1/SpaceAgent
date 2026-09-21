package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface RuntimeSystemAdministrationApi {
    RuntimeOverview overview();
    SystemAdministrationPage<UserResourceSummary> runsByOwner(
            String userId, int page, int pageSize);
    RuntimeDeletionEvidence deletionEvidence(String userId);
    record RuntimeOverview(long runs, long activeRuns, long completedRuns, long failedRuns,
                           long cancelledRuns, long recoveringRuns, Long unknownRuns) {
        public RuntimeOverview(long runs, long activeRuns, long completedRuns, long failedRuns,
                long cancelledRuns, long recoveringRuns, long unknownRuns) {
            this(runs, activeRuns, completedRuns, failedRuns, cancelledRuns, recoveringRuns, Long.valueOf(unknownRuns));
        }
    }
    record RuntimeDeletionEvidence(long activeRuns, long recoveringRuns) {
    }
}
