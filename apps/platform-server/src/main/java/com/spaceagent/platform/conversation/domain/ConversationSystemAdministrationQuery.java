package com.spaceagent.platform.conversation.domain;

import java.time.Instant;
import java.util.List;

public interface ConversationSystemAdministrationQuery {
    OverviewRow overview();
    PageRows<ResourceRow> conversationsByOwner(String userId, int offset, int limit);
    DeletionEvidenceRow deletionEvidence(String userId);
    record OverviewRow(long conversations, long activeConversations, long messages) {
    }
    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }
    record ResourceRow(String id, String organizationId, String parentId, String displayName,
                       String state, String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long primaryCount, long secondaryCount) {
    }
    record DeletionEvidenceRow(long conversations, long messages) {
    }
}
