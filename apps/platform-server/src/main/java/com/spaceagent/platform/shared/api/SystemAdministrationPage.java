package com.spaceagent.platform.shared.api;

import java.time.Instant;
import java.util.List;

public record SystemAdministrationPage<T>(
        List<T> items,
        int page,
        int pageSize,
        long total,
        Instant generatedAt) {

    public SystemAdministrationPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
    }
}
