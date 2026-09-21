package com.spaceagent.platform.knowledge.api;

public record KnowledgeRetrievalMatchView(
        String documentId,
        String chunkId,
        int sequence,
        String content,
        double score,
        String embeddingModel,KnowledgeCollectionRetrievalApi.Citation citation) {
    public KnowledgeRetrievalMatchView(String documentId,String chunkId,int sequence,String content,double score,String model){
        this(documentId,chunkId,sequence,content,score,model,null);
    }
}
