package com.spaceagent.platform.memory.application;

import com.spaceagent.platform.memory.api.MemorySystemAdministrationApi;
import com.spaceagent.platform.memory.domain.MemorySystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class MemorySystemAdministrationService implements MemorySystemAdministrationApi {
    private final MemorySystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public MemorySystemAdministrationService(
            MemorySystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override public SystemAdministrationPage<UserResourceSummary> memoriesByUser(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.memoriesByUser(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                "MEMORY", row.id(), null, null, null, row.state(), row.relation(),
                row.createdAt(), row.updatedAt(), null, 0, 0)).toList(), safePage, safeSize,
                rows.total(), timeProvider.now());
    }
}
