package com.spaceagent.platform.knowledge.domain;
import java.util.List;
import java.util.Map;
public interface KnowledgeDocumentChunker {
    List<Segment> split(String text,List<KnowledgeDocumentParsingGateway.Block> blocks,KnowledgeProcessingPolicy policy);
    int tokens(String text);
    record Segment(String text,Map<String,Object> metadata){public Segment{metadata=Map.copyOf(metadata);}}
}
