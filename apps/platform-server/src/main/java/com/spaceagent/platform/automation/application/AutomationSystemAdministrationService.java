package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.automation.api.AutomationSystemAdministrationApi;
import com.spaceagent.platform.automation.domain.AutomationSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class AutomationSystemAdministrationService implements AutomationSystemAdministrationApi {
    private final AutomationSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public AutomationSystemAdministrationService(
            AutomationSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override public SystemAdministrationPage<UserResourceSummary> schedulesByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.schedulesByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                "AUTOMATION", row.id(), row.organizationId(), row.agentId(), null, row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.executionCount(), row.riskCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }
}
