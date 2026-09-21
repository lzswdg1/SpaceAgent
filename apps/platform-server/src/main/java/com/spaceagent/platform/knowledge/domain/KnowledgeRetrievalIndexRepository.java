package com.spaceagent.platform.knowledge.domain;

import java.util.*;

/** Authoritative published generations and evidence, never vector-store supplied plaintext. */
public interface KnowledgeRetrievalIndexRepository {
    List<Published> published(String baseId,int limit);
    Optional<Evidence> evidence(String baseId,String generationId,String chunkId,String contentHash);
    record EvidenceKey(String baseId, String generationId, String chunkId, String contentHash) {}
    default Map<EvidenceKey, Evidence> evidenceBatch(List<EvidenceKey> keys) {
        if (keys.size() > 100) throw new IllegalArgumentException("Evidence batch exceeds bounds");
        Map<EvidenceKey, Evidence> result = new LinkedHashMap<>();
        for (var key : new LinkedHashSet<>(keys)) evidence(key.baseId(), key.generationId(), key.chunkId(), key.contentHash())
                .ifPresent(value -> result.put(key, value));
        return result;
    }
    record Published(String documentId,String generationId,String spaceId,String schema) {}
    record Evidence(String documentId,String title,String generationId,String chunkId,int ordinal,
                    String content,String contentHash,long documentRevision,Map<String,Object> metadata) {}
}
