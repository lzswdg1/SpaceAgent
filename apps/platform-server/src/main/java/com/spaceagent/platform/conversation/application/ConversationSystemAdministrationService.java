package com.spaceagent.platform.conversation.application;

import com.spaceagent.platform.conversation.api.ConversationSystemAdministrationApi;
import com.spaceagent.platform.conversation.domain.ConversationSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class ConversationSystemAdministrationService implements ConversationSystemAdministrationApi {
    private final ConversationSystemAdministrationQuery query;
    private final TimeProvider timeProvider;
    public ConversationSystemAdministrationService(
            ConversationSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }
    @Override public ConversationOverview overview() {
        var row = query.overview();
        return new ConversationOverview(row.conversations(), row.activeConversations(), row.messages());
    }
    @Override public SystemAdministrationPage<UserResourceSummary> conversationsByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.conversationsByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                "CONVERSATION", row.id(), row.organizationId(), row.parentId(), null, row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.primaryCount(), row.secondaryCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }
    @Override public ConversationDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId);
        return new ConversationDeletionEvidence(row.conversations(), row.messages());
    }
}
