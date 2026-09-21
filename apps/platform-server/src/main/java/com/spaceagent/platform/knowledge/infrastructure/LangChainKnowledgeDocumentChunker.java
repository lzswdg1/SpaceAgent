package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.application.KnowledgeChunkingService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Component
public class LangChainKnowledgeDocumentChunker implements KnowledgeDocumentChunker {
    private final OpenAiTokenCountEstimator tokenizer=new OpenAiTokenCountEstimator("gpt-4o-mini");
    private final Parser markdown=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    public int tokens(String text){return tokenizer.estimateTokenCountInText(text);}
    public List<Segment> split(String text,List<KnowledgeDocumentParsingGateway.Block> blocks,KnowledgeProcessingPolicy policy){
        if(text==null || text.isBlank() || text.length()>1_000_000)throw new IllegalArgumentException("Invalid chunking input");
        List<Section> sections=sections(text,policy);List<Segment> result=new ArrayList<>();
        for(var section:sections){
            String body=text.substring(section.start(),section.end());
            List<String> parts=policy.strategy()==KnowledgeProcessingPolicy.Strategy.FIXED_CHARACTER?
                    new KnowledgeChunkingService(policy.size(),policy.overlap()).chunk(body):
                    DocumentSplitters.recursive(policy.size(),policy.overlap(),tokenizer).split(Document.from(body)).stream().map(s->s.text()).toList();
            int cursor=section.start();
            for(String part:parts){
                for(String bounded:bound(part,policy)){
                    if(result.size()>=16384)throw new IllegalArgumentException("Too many chunks");
                    int start=text.indexOf(bounded,section.start());
                    int duplicate=start<0?-1:text.indexOf(bounded,start+1);
                    Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("strategy",policy.strategy().name());
                    metadata.put("tokenizer","O200K_BASE");metadata.put("tokenCount",tokens(bounded));metadata.put("headingPath",section.headings());
                    if(start>=section.start() && start+bounded.length()<=section.end() && (duplicate<0 || duplicate+bounded.length()>section.end())){
                        int end=start+bounded.length();metadata.put("startOffset",start);metadata.put("endOffset",end);cursor=end;
                        blocks.stream().filter(b->b.page()!=null && b.start()<=start && b.end()>=end).findFirst().ifPresent(b->metadata.put("page",b.page()));
                    } else metadata.put("offsetUnavailable",true);
                    if(parts.size()>1 && (body.contains("```") || body.contains("|")))metadata.put("formatDegraded",true);
                    result.add(new Segment(bounded,metadata));
                }
            }
        }
        return List.copyOf(result);
    }
    private List<String> bound(String value,KnowledgeProcessingPolicy policy){
        if(value.getBytes(StandardCharsets.UTF_8).length<=8000)return List.of(value);
        List<String> results=new ArrayList<>();
        for(String part:new KnowledgeChunkingService(2000,0).chunk(value)){
            if(policy.strategy()==KnowledgeProcessingPolicy.Strategy.FIXED_CHARACTER)results.add(part);
            else results.addAll(DocumentSplitters.recursive(policy.size(),0,tokenizer).split(Document.from(part)).stream().map(s->s.text()).toList());
        }
        return results;
    }
    private List<Section> sections(String text,KnowledgeProcessingPolicy policy){
        if(policy.strategy()!=KnowledgeProcessingPolicy.Strategy.MARKDOWN_SECTION)return List.of(new Section(0,text.length(),List.of()));
        List<Integer> lines=new ArrayList<>();lines.add(0);for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')lines.add(i+1);
        List<Heading> headings=new ArrayList<>();markdown.parse(text).accept(new AbstractVisitor(){@Override public void visit(Heading heading){headings.add(heading);}});
        List<Section> sections=new ArrayList<>();List<String> path=new ArrayList<>();int start=0;
        for(var heading:headings){if(heading.getSourceSpans().isEmpty())continue;int at=lines.get(heading.getSourceSpans().getFirst().getLineIndex());
            if(at>start)sections.add(new Section(start,at,List.copyOf(path)));
            while(path.size()>=heading.getLevel())path.removeLast();while(path.size()<heading.getLevel()-1)path.add("");
            int end=text.indexOf('\n',at);String title=text.substring(at,end<0?text.length():end).replaceFirst("^ {0,3}#{1,6}\\s+","").trim();
            path.add(title.substring(0,Math.min(title.length(),256)));start=at;
        }
        if(start<text.length())sections.add(new Section(start,text.length(),List.copyOf(path)));return sections;
    }
    private record Section(int start,int end,List<String> headings){}
}
