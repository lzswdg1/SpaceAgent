package com.spaceagent.platform.memory.domain;

import java.time.Instant;
import java.util.List;

public interface MemorySystemAdministrationQuery {
    PageRows<ResourceRow> memoriesByUser(String userId, int offset, int limit);

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record ResourceRow(String id, String state, String relation,
                       Instant createdAt, Instant updatedAt) {
    }
}
