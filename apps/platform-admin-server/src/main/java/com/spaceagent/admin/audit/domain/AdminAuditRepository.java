package com.spaceagent.admin.audit.domain;

public interface AdminAuditRepository {
    void append(AdminAuditEvent event);

    AdminAuditPage page(int offset, int limit, java.util.UUID actorId, String action, String targetId);
}
