package com.spaceagent.platform.knowledge.domain;

import java.util.Optional;

public interface KnowledgeIndexIntakeRepository {
    Optional<Receipt> find(String baseId,String actorId,String key);
    void insert(Receipt receipt);
    default void linkLegacy(String generationId,String sourceDocumentId){throw new UnsupportedOperationException();}
    default Optional<Receipt> latestLegacyCopy(String actor,String sourceDocumentId){return Optional.empty();}
    record Receipt(String baseId,String actorId,String key,String requestHash,String documentId,String generationId,
                   String jobId,long documentRevision,String sourceKind,String sourceId,String mediaType,
                   String originalHash,String originalReference,String charset,String normalizationVersion,long originalBytes) {}
}
