package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.RuntimeSystemAdministrationApi;
import com.spaceagent.platform.runtime.domain.RuntimeSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class RuntimeSystemAdministrationService implements RuntimeSystemAdministrationApi {
    private final RuntimeSystemAdministrationQuery query;
    private final TimeProvider timeProvider;
    public RuntimeSystemAdministrationService(
            RuntimeSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }
    @Override public RuntimeOverview overview() {
        var row = query.overview();
        return new RuntimeOverview(row.runs(), row.activeRuns(), row.completedRuns(), row.failedRuns(),
                row.cancelledRuns(), row.recoveringRuns(), row.unknownRuns());
    }
    @Override public SystemAdministrationPage<UserResourceSummary> runsByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.runsByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                "RUN", row.id(), row.organizationId(), row.parentId(), null, row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.primaryCount(), row.secondaryCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }
    @Override public RuntimeDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId);
        return new RuntimeDeletionEvidence(row.activeRuns(), row.recoveringRuns());
    }
}
