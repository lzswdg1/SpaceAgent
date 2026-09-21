package com.spaceagent.platform.knowledge.api;

public interface KnowledgeIndexIntakeApplicationApi {
    IntakeView file(FileCommand command);
    IntakeView urlSnapshot(UrlCommand command);
    DocumentView document(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId);
    record FileCommand(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId,long expectedRevision,
                       String name,String spaceId,String mediaType,byte[] bytes,String idempotencyKey) {
        public FileCommand {bytes=bytes==null?new byte[0]:bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "KnowledgeFileIntake[content redacted]";}
    }
    record UrlCommand(KnowledgeBaseApplicationApi.Actor actor,String baseId,String documentId,long expectedRevision,
                      String name,String spaceId,String urlJobId,String versionId,String idempotencyKey) {}
    record IntakeView(String documentId,long documentRevision,String generationId,String jobId,String state) {}
    record DocumentView(String documentId,String name,String scope,String ownerId,long revision,String state,String activeGenerationId) {}
}
