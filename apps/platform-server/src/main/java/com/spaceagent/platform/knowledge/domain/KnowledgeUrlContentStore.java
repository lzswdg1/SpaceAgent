package com.spaceagent.platform.knowledge.domain;

public interface KnowledgeUrlContentStore {
    String put(String urlJobId,String contentSha256,byte[] content);
    byte[] read(String objectReference,int maximumBytes);
}
