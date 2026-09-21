package com.spaceagent.platform.knowledge.domain;

import java.util.Optional;
import java.util.List;

/**
 * Domain port for knowledge document persistence.
 */
public interface KnowledgeDocumentRepository {
    Optional<KnowledgeDocument> findById(String id);

    List<KnowledgeDocument> findByOwnerId(String ownerId);

    default List<KnowledgeDocument> findReadyByOwnerId(String ownerId, int limit) {
        return findByOwnerId(ownerId).stream()
                .filter(document -> document.status() == KnowledgeDocumentStatus.READY)
                .limit(limit)
                .toList();
    }

    default List<KnowledgeDocument> findByIds(List<String> ids) {
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    void save(KnowledgeDocument document);

    void updateStatus(String documentId, KnowledgeDocumentStatus status, String errorReason);

    void delete(String documentId);
}
