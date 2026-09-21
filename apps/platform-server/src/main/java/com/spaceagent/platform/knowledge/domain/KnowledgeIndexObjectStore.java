package com.spaceagent.platform.knowledge.domain;

/** Owner-managed, content-addressed intermediate outputs, never arbitrary URLs or host paths. */
public interface KnowledgeIndexObjectStore {
    String put(String jobId,String sha256,byte[] bytes);
    byte[] read(String reference,String expectedHash);
    default boolean deleteOwner(String owner,int limit) {throw new UnsupportedOperationException("Object deletion is not configured");}
    default void deleteReference(String reference) {throw new UnsupportedOperationException("Object deletion is not configured");}
    default void deleteStalePartials(String owner) {}
}
