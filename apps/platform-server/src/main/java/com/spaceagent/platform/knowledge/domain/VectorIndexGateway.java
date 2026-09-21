package com.spaceagent.platform.knowledge.domain;

import java.util.List;

/** Internal retrieval index port; Knowledge owns authorization, generation fences and publication. */
public interface VectorIndexGateway {
    /** Local capability validation, before paid query/build embeddings; must not perform effects. */
    default void validateSpace(Space space) {}
    void ensureSpace(Space space);
    default boolean spaceExists(Space space) { return true; }
    void upsertBatch(Space space, List<Entry> entries);
    boolean verifyBatch(Space space, List<Entry> entries);
    List<Match> search(Space space, Scope scope, List<Double> queryVector, int topK);
    default List<Match> lexicalSearch(Space space,Scope scope,String query,int topK){return List.of();}
    void deleteGeneration(Space space, Scope scope, String generationId);
    boolean generationDeleted(Space space, Scope scope, String generationId);

    record Space(String id, String fingerprint, int dimensions,String schema) {
        public Space(String id,String fingerprint,int dimensions){this(id,fingerprint,dimensions,"dense_v1");}
        public Space {
            if(!java.util.Set.of("dense_v1","hybrid_v2").contains(schema))throw new IllegalArgumentException("Unknown vector schema");
            identifier(id,36);
            if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}") || dimensions<1 || dimensions>32768)
                throw new IllegalArgumentException("Invalid vector space");
        }
    }
    record Scope(String key,String baseId,List<String> generationIds) {
        public Scope {
            identifier(key,96); identifier(baseId,36);
            generationIds=generationIds==null?List.of():List.copyOf(generationIds);
            if(generationIds.isEmpty() || generationIds.size()>64)throw new IllegalArgumentException("An explicit bounded generation scope is required");
            generationIds.forEach(id->identifier(id,36));
        }
    }
    record Entry(String scopeKey,String baseId,String documentId,String generationId,String chunkId,
                 String contentHash,List<Double> vector,String text) {
        public Entry(String scope,String base,String doc,String gen,String chunk,String hash,List<Double> vector){this(scope,base,doc,gen,chunk,hash,vector,null);}
        public Entry {
            identifier(scopeKey,96); identifier(baseId,36); identifier(documentId,36);
            identifier(generationId,36); identifier(chunkId,36);
            if(contentHash==null || !contentHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid chunk hash");
            vector=List.copyOf(vector);
        }
    }
    record Match(String documentId,String generationId,String chunkId,String contentHash,double score) {}
    private static void identifier(String value,int max) {
        if(value==null || value.length()>max || !value.matches("[A-Za-z0-9_:.-]+"))
            throw new IllegalArgumentException("Invalid vector index identifier");
    }
}
