package com.spaceagent.platform.knowledge.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlContentParser;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentParsingGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

/** Binary parser runs outside the business JVM; local text normalization uses existing bounded components. */
@Component
public class TikaDocumentParsingGateway implements KnowledgeDocumentParsingGateway {
    public static final String PDF="application/pdf",DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final RestClient client;private final KnowledgeUrlContentParser text;private final ObjectMapper json;
    public TikaDocumentParsingGateway(KnowledgeUrlContentParser text,ObjectMapper json,
            @Value("${platform.knowledge.parser.uri:}") String endpoint,
            @Value("${platform.knowledge.parser.allow-private-http:false}") boolean privateHttp){
        this.text=text;this.json=json;
        if(endpoint.isBlank()){client=null;return;}
        URI uri=URI.create(endpoint);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || privateHttp && "http".equals(uri.getScheme()) && Set.of("127.0.0.1","localhost","rag-parser","rag-parser-gateway").contains(uri.getHost())))
            throw new IllegalArgumentException("Tika endpoint requires HTTPS or explicit private local configuration");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(30));client=RestClient.builder().baseUrl(endpoint.replaceAll("/+$","")).requestFactory(factory).build();
    }
    public boolean supports(String type){return !Set.of(PDF,DOCX).contains(type) || client!=null;}
    public Parsed parse(byte[] bytes,String media,String charset){
        if(bytes==null || bytes.length==0 || bytes.length>8_000_000)throw invalid("KNOWLEDGE_PARSE_SIZE_INVALID");
        if(!Set.of(PDF,DOCX).contains(media)) {
            String value=text.parse(bytes,media,charset);return new Parsed(value,List.of(new Block(0,value.length(),null,List.of())),"BOUNDED_TEXT_V1",List.of());
        }
        if(client==null)throw unavailable();
        if(DOCX.equals(media))validateZip(bytes);
        try {
            String version=client.get().uri("/version").retrieve().body(String.class);
            if(version==null || !version.contains("3.3.0"))throw unavailable();
            byte[] result=client.put().uri("/rmeta").contentType(org.springframework.http.MediaType.parseMediaType(media))
                    .header("Accept","application/json").header("writeLimit","1000000").header("maxEmbeddedResources","0")
                    .header("X-Tika-Skip-Embedded","true").header("X-Tika-PDFOcrStrategy","no_ocr")
                    .body(bytes).exchange((request,response)->{
                        if(!response.getStatusCode().is2xxSuccessful())throw invalid("KNOWLEDGE_BINARY_PARSE_REJECTED");
                        byte[] raw=response.getBody().readNBytes(8_000_001);if(raw.length>8_000_000)throw invalid("KNOWLEDGE_PARSE_OUTPUT_LIMIT");return raw;
                    });
            var root=json.readTree(result);if(!root.isArray() || root.isEmpty())throw invalid("KNOWLEDGE_PARSE_RESPONSE_INVALID");
            var metadata=root.get(0);
            List<String> warnings=new ArrayList<>();
            for(var names=metadata.fieldNames();names.hasNext();) {String key=names.next();
                // maxEmbeddedResources=0 deliberately excludes attachments, including Tika's root counter marker.
                if(key.equals("X-TIKA:EXCEPTION:embedded_resource_limit_reached")){warnings.add("EMBEDDED_RESOURCES_EXCLUDED");continue;}
                if(key.startsWith("X-TIKA:EXCEPTION") || key.contains("write_limit_reached"))throw invalid("KNOWLEDGE_PARSE_INCOMPLETE");}
            String html=metadata.path("X-TIKA:content").asText("");var document=Jsoup.parse(html);document.select("script,style,iframe,object,embed").remove();
            var pages=PDF.equals(media)?document.select("div.page"):new org.jsoup.select.Elements();
            List<Block> blocks=new ArrayList<>();StringBuilder content=new StringBuilder();
            if(!pages.isEmpty())for(int i=0;i<pages.size();i++) {
                String value=pages.get(i).text().trim();if(value.isEmpty())continue;if(!content.isEmpty())content.append("\n\n");
                int start=content.length();content.append(value);blocks.add(new Block(start,content.length(),i+1,List.of()));
            } else {content.append(document.body().text().trim());blocks.add(new Block(0,content.length(),null,List.of()));}
            if(content.isEmpty())throw invalid("KNOWLEDGE_NO_EXTRACTABLE_TEXT");if(content.length()>1_000_000 || blocks.size()>10000)throw invalid("KNOWLEDGE_PARSE_OUTPUT_LIMIT");
            if(pages.isEmpty())warnings.add("PAGE_LOCATIONS_UNAVAILABLE");
            return new Parsed(content.toString(),blocks,"TIKA_3.3.0_XHTML_V1",warnings);
        }catch(BusinessException e){throw e;}catch(Exception e){throw unavailable();}
    }
    private static void validateZip(byte[] bytes){
        if(bytes.length<4 || bytes[0]!='P' || bytes[1]!='K')throw invalid("KNOWLEDGE_DOCX_CONTAINER_INVALID");
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){int count=0;long total=0;boolean document=false;byte[] buffer=new byte[16384];
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if(++count>2048 || entry.getName().startsWith("/") || entry.getName().contains("..") || entry.getName().contains("\\"))throw invalid("KNOWLEDGE_ARCHIVE_REJECTED");
                document|=entry.getName().equals("word/document.xml");int read;while((read=zip.read(buffer))!=-1){total+=read;if(total>32_000_000)throw invalid("KNOWLEDGE_ARCHIVE_EXPANSION_LIMIT");}
            }
            if(!document)throw invalid("KNOWLEDGE_DOCX_CONTAINER_INVALID");
        }catch(BusinessException e){throw e;}catch(Exception e){throw invalid("KNOWLEDGE_DOCX_CONTAINER_INVALID");}
    }
    private static BusinessException invalid(String code){return new BusinessException("Document could not be safely parsed",HttpStatus.UNPROCESSABLE_ENTITY,code);}
    private static BusinessException unavailable(){return new BusinessException("Isolated document parser is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_PARSER_UNAVAILABLE");}
}
