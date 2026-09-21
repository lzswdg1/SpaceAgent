package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;

public interface KnowledgeSystemAdministrationQuery {
    PageRows<ResourceRow> documentsByOwner(String userId, int offset, int limit);

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record ResourceRow(String id, String relation, String state, Instant createdAt,
                       Instant updatedAt, String safeErrorCode, long chunkCount) {
    }
}
