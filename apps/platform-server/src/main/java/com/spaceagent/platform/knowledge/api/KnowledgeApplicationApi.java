package com.spaceagent.platform.knowledge.api;

import java.util.List;
import java.util.Optional;

/**
 * Public knowledge application API used by other platform-server modules.
 */
public interface KnowledgeApplicationApi {

    KnowledgeDocumentView createDocument(CreateKnowledgeDocumentCommand command);

    Optional<KnowledgeDocumentView> findDocument(String documentId);

    KnowledgeChunkView addChunk(AddKnowledgeChunkCommand command);

    List<KnowledgeChunkView> findChunksByDocument(String documentId);

    List<KnowledgeDocumentView> findDocumentsByOwner(String ownerId);

    KnowledgeDocumentView markDocumentStatus(MarkKnowledgeDocumentStatusCommand command);

    KnowledgeProcessingView process(ProcessKnowledgeDocumentCommand command);

    KnowledgeRetrievalView retrieve(KnowledgeRetrievalCommand command);

    void deleteDocument(String documentId);
}
