package com.spaceagent.platform.knowledge.domain;

import java.util.List;
import java.util.Optional;

public interface KnowledgeIndexCatalogRepository {
    Optional<KnowledgeIndexGeneration> findGeneration(String baseId,String generationId);
    Optional<KnowledgeDocumentScope> findDocumentScope(String documentId);
    /** Preserve an existing assignment; never move a document implicitly. */
    KnowledgeDocumentScope insertScopeIfAbsent(KnowledgeDocumentScope scope);
    List<KnowledgeDocumentScope> listDocuments(String baseId, int offset, int limit);
    KnowledgeEmbeddingSpace insertSpaceIfAbsent(KnowledgeEmbeddingSpace space);
    Optional<KnowledgeEmbeddingSpace> findSpace(String baseId, String spaceId);
    List<KnowledgeEmbeddingSpace> listSpaces(String baseId, int offset, int limit);
    KnowledgeIndexGeneration insertGenerationIfAbsent(KnowledgeIndexGeneration generation);
    List<KnowledgeIndexGeneration> listGenerations(String baseId, String documentId, int offset, int limit);
}
