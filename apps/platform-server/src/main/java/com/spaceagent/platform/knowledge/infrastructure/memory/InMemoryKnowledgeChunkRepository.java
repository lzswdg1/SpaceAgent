package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeChunk;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference in-module knowledge-chunk repository.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final Map<String, KnowledgeChunk> chunks = new ConcurrentHashMap<>();

    @Override
    public List<KnowledgeChunk> findByDocumentId(String documentId) {
        return chunks.values().stream()
                .filter(chunk -> documentId.equals(chunk.documentId()))
                .sorted(Comparator.comparingInt(KnowledgeChunk::sequence))
                .toList();
    }

    @Override
    public List<KnowledgeChunk> findByDocumentIds(List<String> documentIds) {
        java.util.Set<String> selected = java.util.Set.copyOf(documentIds);
        return chunks.values().stream().filter(value -> selected.contains(value.documentId())).toList();
    }

    @Override
    public void save(KnowledgeChunk chunk) {
        chunks.put(chunk.id(), chunk);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        chunks.values().removeIf(chunk -> documentId.equals(chunk.documentId()));
    }
}
