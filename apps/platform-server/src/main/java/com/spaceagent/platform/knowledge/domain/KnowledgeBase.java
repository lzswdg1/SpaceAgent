package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.Objects;

/** A knowledge collection, distinct from legacy Agent-held document IDs. */
public record KnowledgeBase(String id, Scope scope, String organizationId, String ownerId,
                            String name, String description, State state, long revision,
                            Instant createdAt, Instant updatedAt) {
    public enum Scope { PERSONAL, ORGANIZATION }
    public enum State { ACTIVE, ARCHIVED }
    public enum Permission {
        READ, WRITE, MANAGE;
        public boolean includes(Permission required) { return ordinal() >= required.ordinal(); }
    }

    public KnowledgeBase {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (id == null || id.isBlank() || id.length() > 36 || revision < 1
                || name == null || name.isBlank() || name.length() > 255
                || (description != null && description.length() > 2000)) {
            throw new IllegalArgumentException("Invalid knowledge base");
        }
        if (scope == Scope.PERSONAL && (organizationId != null || ownerId == null)
                || scope == Scope.ORGANIZATION && (organizationId == null || organizationId.isBlank())) {
            throw new IllegalArgumentException("Invalid knowledge base scope");
        }
    }

    public KnowledgeBase revise(String name, String description, State state, Instant now) {
        return new KnowledgeBase(id, scope, organizationId, ownerId, name, description, state,
                revision + 1, createdAt, now);
    }
}
