package com.spaceagent.platform.knowledge.domain;

import java.util.List;

/** Durable inventory is advisory for GC, never an authorization source. */
public interface KnowledgeObjectInventory {
    void record(String reference,String owner);
    void lockOwner(String owner);
    List<String> orphanCandidates(int limit);
    boolean deleteIfOrphan(String reference,KnowledgeIndexObjectStore objects);
}
