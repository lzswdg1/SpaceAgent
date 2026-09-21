package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.KnowledgeSystemAdministrationApi;
import com.spaceagent.platform.knowledge.domain.KnowledgeSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class KnowledgeSystemAdministrationService implements KnowledgeSystemAdministrationApi {
    private final KnowledgeSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public KnowledgeSystemAdministrationService(
            KnowledgeSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override public SystemAdministrationPage<UserResourceSummary> documentsByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.documentsByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                "KNOWLEDGE_DOCUMENT", row.id(), null, null, null, row.state(), row.relation(),
                row.createdAt(), row.updatedAt(), row.safeErrorCode(), row.chunkCount(), 0)).toList(),
                safePage, safeSize, rows.total(), timeProvider.now());
    }
}
