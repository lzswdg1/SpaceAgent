package com.spaceagent.platform.knowledge.api;

import java.util.*;

public interface KnowledgeCollectionRetrievalApi {
    Result retrieve(Query query);
    record Query(KnowledgeBaseApplicationApi.Actor actor,List<String> baseIds,String query,int topK,int maxContextTokens,String operationKey){
        public Query {baseIds=baseIds==null?List.of():List.copyOf(baseIds);}
        @Override public String toString(){return "KnowledgeQuery[redacted]";}
    }
    record Citation(String baseId,String documentId,String title,String generationId,String chunkId,long documentRevision,
                    String contentHash,Map<String,Object> location){public Citation{location=Map.copyOf(location);}}
    record Hit(String content,double rankScore,Citation citation){
        public String rendered(){return "[source "+citation.documentId()+"#"+citation.chunkId()+"; generation="+citation.generationId()+"]\n"+content;}
    }
    record Result(List<Hit> hits,boolean partial,boolean degraded,boolean insufficientEvidence,int contextTokens,List<String> warnings,
                  String ranking,String rerankerModel,Map<String,Long> stageMillis){
        public Result(List<Hit> hits,boolean partial,boolean degraded,boolean insufficientEvidence,int contextTokens,List<String> warnings){this(hits,partial,degraded,insufficientEvidence,contextTokens,warnings,"RRF",null,Map.of());}
        public Result{hits=List.copyOf(hits);warnings=List.copyOf(warnings);stageMillis=Map.copyOf(stageMillis);}
        public KnowledgeRetrievalView legacyView(String query){return new KnowledgeRetrievalView(query,hits.stream().map(h->
            new KnowledgeRetrievalMatchView(h.citation().documentId(),h.citation().chunkId(),0,h.rendered(),h.rankScore(),"MILVUS_RRF",h.citation())).toList(),partial,degraded,warnings,stageMillis);}
    }
}
