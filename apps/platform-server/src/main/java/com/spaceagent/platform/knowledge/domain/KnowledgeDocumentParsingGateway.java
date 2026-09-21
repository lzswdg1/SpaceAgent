package com.spaceagent.platform.knowledge.domain;
import java.util.List;
public interface KnowledgeDocumentParsingGateway {
    Parsed parse(byte[] bytes,String mediaType,String charset);
    boolean supports(String mediaType);
    record Block(int start,int end,Integer page,List<String> headings){public Block{headings=List.copyOf(headings);}}
    record Parsed(String text,List<Block> blocks,String parser,List<String> warnings){public Parsed{blocks=List.copyOf(blocks);warnings=List.copyOf(warnings);}}
}
