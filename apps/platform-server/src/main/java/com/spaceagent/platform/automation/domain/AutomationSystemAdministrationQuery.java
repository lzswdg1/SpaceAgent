package com.spaceagent.platform.automation.domain;

import java.time.Instant;
import java.util.List;

public interface AutomationSystemAdministrationQuery {
    PageRows<ResourceRow> schedulesByOwner(String userId, int offset, int limit);

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record ResourceRow(String id, String organizationId, String agentId, String state,
                       String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long executionCount, long riskCount) {
    }
}
