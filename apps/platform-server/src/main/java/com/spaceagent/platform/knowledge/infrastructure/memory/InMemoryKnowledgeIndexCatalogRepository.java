package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix="platform", name="persistence", havingValue="memory", matchIfMissing=true)
public class InMemoryKnowledgeIndexCatalogRepository implements KnowledgeIndexCatalogRepository {
    private final Map<String, KnowledgeDocumentScope> documents = new HashMap<>();
    private final Map<String, KnowledgeEmbeddingSpace> spaces = new HashMap<>();
    private final Map<String, KnowledgeIndexGeneration> generations = new HashMap<>();
    @Override public synchronized Optional<KnowledgeIndexGeneration> findGeneration(String base,String id) {
        return Optional.ofNullable(generations.get(id)).filter(g->g.baseId().equals(base));
    }
    @Override public synchronized Optional<KnowledgeDocumentScope> findDocumentScope(String documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }
    @Override public synchronized KnowledgeDocumentScope insertScopeIfAbsent(KnowledgeDocumentScope scope) {
        return documents.computeIfAbsent(scope.documentId(), ignored -> scope);
    }
    @Override public synchronized List<KnowledgeDocumentScope> listDocuments(String base, int offset, int limit) {
        return documents.values().stream().filter(v -> v.baseId().equals(base))
                .sorted(Comparator.comparing(KnowledgeDocumentScope::documentId)).skip(offset).limit(limit).toList();
    }
    @Override public synchronized KnowledgeEmbeddingSpace insertSpaceIfAbsent(KnowledgeEmbeddingSpace space) {
        return spaces.values().stream().filter(v -> v.baseId().equals(space.baseId()) && v.fingerprint().equals(space.fingerprint()))
                .findFirst().orElseGet(() -> { spaces.put(space.id(), space); return space; });
    }
    @Override public synchronized Optional<KnowledgeEmbeddingSpace> findSpace(String base, String id) {
        return Optional.ofNullable(spaces.get(id)).filter(v -> v.baseId().equals(base));
    }
    @Override public synchronized List<KnowledgeEmbeddingSpace> listSpaces(String base, int offset, int limit) {
        return spaces.values().stream().filter(v -> v.baseId().equals(base))
                .sorted(Comparator.comparing(KnowledgeEmbeddingSpace::id)).skip(offset).limit(limit).toList();
    }
    @Override public synchronized KnowledgeIndexGeneration insertGenerationIfAbsent(KnowledgeIndexGeneration generation) {
        return generations.values().stream().filter(v -> v.baseId().equals(generation.baseId()) && v.fingerprint().equals(generation.fingerprint()))
                .findFirst().orElseGet(() -> { generations.put(generation.id(), generation); return generation; });
    }
    @Override public synchronized List<KnowledgeIndexGeneration> listGenerations(String base, String document, int offset, int limit) {
        return generations.values().stream().filter(v -> v.baseId().equals(base) && v.documentId().equals(document))
                .sorted(Comparator.comparing(KnowledgeIndexGeneration::id)).skip(offset).limit(limit).toList();
    }
}
