package com.spaceagent.platform.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.shared.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.nio.*;import java.nio.charset.*;import java.util.Set;

@Component
public class KnowledgeUrlContentParser {
    private static final int MAX_CHARACTERS=1_000_000;private final ObjectMapper json;
    public KnowledgeUrlContentParser(ObjectMapper json){this.json=json;}
    public String parse(byte[] body,String mediaTypeValue,String charsetValue){try{if(body==null||body.length>10_000_000)throw invalid("KNOWLEDGE_URL_CONTENT_TOO_LARGE");MediaType media=MediaType.parseMediaType(mediaTypeValue);Charset charset=Charset.forName(charsetValue);CharsetDecoder decoder=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);String raw=decoder.decode(ByteBuffer.wrap(body)).toString();String type=media.getType()+"/"+media.getSubtype();String parsed=switch(type){case "text/html","application/xhtml+xml"->{var doc=Jsoup.parse(raw);doc.select("script,style,iframe,object,embed,form,template,noscript").remove();yield doc.text();}case "application/json"->{json.readTree(raw);yield raw;}case "application/xml","application/rss+xml","application/atom+xml"->Jsoup.parse(raw,"",Parser.xmlParser()).text();case "text/plain","text/markdown","text/csv"->raw;default->throw invalid("KNOWLEDGE_URL_PARSER_UNSUPPORTED");};parsed=parsed.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]"," ").trim();if(parsed.isBlank())throw invalid("KNOWLEDGE_URL_CONTENT_EMPTY");if(parsed.length()>MAX_CHARACTERS)throw invalid("KNOWLEDGE_URL_CONTENT_TOO_LARGE");return parsed;}catch(BusinessException e){throw e;}catch(CharacterCodingException e){throw invalid("KNOWLEDGE_URL_ENCODING_INVALID");}catch(Exception e){throw invalid("KNOWLEDGE_URL_PARSE_INVALID");}}
    private static BusinessException invalid(String code){return new BusinessException("Knowledge URL content is invalid",HttpStatus.UNPROCESSABLE_ENTITY,code);}
}
