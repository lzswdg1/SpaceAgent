package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference in-module knowledge-document repository.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();

    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public List<KnowledgeDocument> findByOwnerId(String ownerId) {
        return documents.values().stream()
                .filter(document -> ownerId.equals(document.ownerId()))
                .toList();
    }

    @Override
    public List<KnowledgeDocument> findReadyByOwnerId(String ownerId, int limit) {
        return documents.values().stream()
                .filter(document -> ownerId.equals(document.ownerId()))
                .filter(document -> document.status() == KnowledgeDocumentStatus.READY)
                .sorted(java.util.Comparator.comparing(KnowledgeDocument::createdAt).reversed()
                        .thenComparing(KnowledgeDocument::id, java.util.Comparator.reverseOrder()))
                .limit(Math.max(1, Math.min(limit, 64)))
                .toList();
    }

    @Override
    public List<KnowledgeDocument> findByIds(List<String> ids) {
        return ids.stream().map(documents::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void save(KnowledgeDocument document) {
        documents.put(document.id(), document);
    }

    @Override
    public void updateStatus(String documentId, KnowledgeDocumentStatus status, String errorReason) {
        documents.computeIfPresent(documentId, (id, current) -> new KnowledgeDocument(
                current.id(),
                current.ownerId(),
                current.name(),
                current.contentType(),
                current.storageLocation(),
                status,
                current.createdAt(),
                java.time.Instant.now()
        ));
    }

    @Override
    public void delete(String documentId) {
        documents.remove(documentId);
    }
}
