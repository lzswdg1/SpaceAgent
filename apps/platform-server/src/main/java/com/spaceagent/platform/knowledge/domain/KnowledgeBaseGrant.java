package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.Objects;

/** A live grant; membership is revalidated when it is used. */
public record KnowledgeBaseGrant(String baseId, String organizationId, SubjectType subjectType,
                                 String subjectId, KnowledgeBase.Permission permission,
                                 String grantedBy, Instant updatedAt) {
    public enum SubjectType { USER, ROLE }
    public KnowledgeBaseGrant {
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (baseId == null || baseId.isBlank() || organizationId == null || organizationId.isBlank()
                || subjectId == null || subjectId.isBlank() || subjectId.length() > 64
                || grantedBy == null || grantedBy.isBlank()) {
            throw new IllegalArgumentException("Invalid knowledge base grant");
        }
        if (subjectType == SubjectType.ROLE
                && !java.util.Set.of("OWNER", "ADMIN", "MEMBER", "VIEWER").contains(subjectId)) {
            throw new IllegalArgumentException("Invalid grant role");
        }
    }
}
