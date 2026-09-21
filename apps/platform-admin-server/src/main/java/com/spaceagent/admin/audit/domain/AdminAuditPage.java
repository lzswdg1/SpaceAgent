package com.spaceagent.admin.audit.domain;

import java.util.List;

public record AdminAuditPage(List<AdminAuditEvent> items, long total) {
    public AdminAuditPage { items = items == null ? List.of() : List.copyOf(items); }
}
