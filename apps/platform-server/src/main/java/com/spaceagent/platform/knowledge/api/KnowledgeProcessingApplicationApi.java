package com.spaceagent.platform.knowledge.api;
import com.spaceagent.platform.knowledge.domain.*;
import java.util.List;
public interface KnowledgeProcessingApplicationApi {
    PolicyView policy(KnowledgeBaseApplicationApi.Actor actor,String base);
    PolicyView configure(KnowledgeBaseApplicationApi.Actor actor,String base,long revision,KnowledgeProcessingPolicy policy);
    List<KnowledgeDocumentChunker.Segment> preview(KnowledgeBaseApplicationApi.Actor actor,String base,String text,KnowledgeProcessingPolicy policy);
    record PolicyView(long revision,KnowledgeProcessingPolicy policy){}
}
