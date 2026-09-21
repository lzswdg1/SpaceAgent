package com.spaceagent.platform.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {
    Optional<KnowledgeBase> find(String id);
    Optional<KnowledgeBase> lock(String id);
    void insert(KnowledgeBase base);
    KnowledgeBase insertIfAbsent(KnowledgeBase base);
    boolean update(KnowledgeBase base, long expectedRevision);
    /** Filters before pagination, using role truth already checked by the application. */
    List<KnowledgeBase> listVisible(String actorId, String organizationId, String role, int offset, int limit);
    List<KnowledgeBaseGrant> grants(String baseId);
    void putGrant(KnowledgeBaseGrant grant);
    void removeGrant(String baseId, KnowledgeBaseGrant.SubjectType type, String subjectId);
}
