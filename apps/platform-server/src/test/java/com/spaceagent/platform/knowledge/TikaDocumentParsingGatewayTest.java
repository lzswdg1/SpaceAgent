package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlContentParser;
import com.spaceagent.platform.knowledge.infrastructure.TikaDocumentParsingGateway;
import com.spaceagent.shared.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

public class TikaDocumentParsingGatewayTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static TikaDocumentParsingGateway gateway(String uri){return new TikaDocumentParsingGateway(new KnowledgeUrlContentParser(JSON),JSON,uri,true);}
    @Test void textFallbackIsBoundedAndBinaryRequiresAnIsolatedService(){
        var parser=gateway("");assertThat(parser.supports(TikaDocumentParsingGateway.PDF)).isFalse();
        assertThat(parser.parse("<h1>Title</h1><script>ignored()</script>".getBytes(StandardCharsets.UTF_8),"text/html","UTF-8").text()).isEqualTo("Title");
        assertThatThrownBy(()->parser.parse(new byte[]{1},TikaDocumentParsingGateway.PDF,"UTF-8")).isInstanceOf(BusinessException.class);
    }
    @Test void xhtmlPagesAreRealLocationsAndIncompleteOutputIsRejected() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var bad=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/version",e->{byte[] b="Apache Tika 3.3.0".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/rmeta",e->{e.getRequestBody().readAllBytes();var m=new HashMap<String,Object>();m.put("X-TIKA:content","<html><body><div class='page'>First page</div><div class='page'>Second page</div></body></html>");
            if(bad.get())m.put("X-TIKA:EXCEPTION:write_limit_reached","true");byte[] b=JSON.writeValueAsBytes(List.of(m));e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        server.start();try{
            var parser=gateway("http://127.0.0.1:"+server.getAddress().getPort());var result=parser.parse(new byte[]{1},TikaDocumentParsingGateway.PDF,"UTF-8");
            assertThat(result.blocks()).extracting(b->b.page()).containsExactly(1,2);assertThat(result.text()).contains("First page","Second page");
            bad.set(true);assertThatThrownBy(()->parser.parse(new byte[]{1},TikaDocumentParsingGateway.PDF,"UTF-8")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("KNOWLEDGE_PARSE_INCOMPLETE"));
        }finally{server.stop(0);}
    }
    @Test void archiveExpansionBombIsStoppedBeforeRemoteParsing() throws Exception {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("word/document.xml"));byte[] part=new byte[32768];Arrays.fill(part,(byte)'x');for(int i=0;i<1024;i++)zip.write(part);zip.closeEntry();}
        assertThatThrownBy(()->gateway("http://127.0.0.1:1").parse(bytes.toByteArray(),TikaDocumentParsingGateway.DOCX,"UTF-8"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("KNOWLEDGE_ARCHIVE_EXPANSION_LIMIT"));
    }
    @Test @EnabledIfEnvironmentVariable(named="SPACEAGENT_TIKA_TEST_URI",matches=".+")
    void realIsolatedTikaExtractsPdfAndDocxWithoutInventingDocxPageNumbers() throws Exception {
        var parser=gateway(System.getenv("SPACEAGENT_TIKA_TEST_URI"));
        var pdf=parser.parse(pdf(),TikaDocumentParsingGateway.PDF,"UTF-8");assertThat(pdf.text()).contains("SpaceAgent PDF fixture");
        assertThat(pdf.blocks()).anyMatch(b->Integer.valueOf(1).equals(b.page()));
        var docx=parser.parse(docx(),TikaDocumentParsingGateway.DOCX,"UTF-8");assertThat(docx.text()).contains("SpaceAgent DOCX fixture");
        assertThat(docx.blocks()).allMatch(b->b.page()==null);
    }
    public static byte[] docx() throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){
            entry(zip,"[Content_Types].xml","<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>");
            entry(zip,"word/document.xml","<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>SpaceAgent DOCX fixture</w:t></w:r></w:p></w:body></w:document>");
        }return bytes.toByteArray();
    }
    private static void entry(ZipOutputStream zip,String name,String value) throws IOException {var e=new ZipEntry(name);e.setTime(0);zip.putNextEntry(e);zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    public static byte[] pdf() {
        String content="BT /F1 12 Tf 50 750 Td (SpaceAgent PDF fixture) Tj ET";
        List<String> objects=List.of("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>","<< /Length "+content.length()+" >>\nstream\n"+content+"\nendstream");
        StringBuilder out=new StringBuilder("%PDF-1.4\n");List<Integer> offsets=new ArrayList<>();for(int i=0;i<objects.size();i++){offsets.add(out.length());out.append(i+1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");}
        int xref=out.length();out.append("xref\n0 6\n0000000000 65535 f \n");for(int offset:offsets)out.append(String.format(Locale.ROOT,"%010d 00000 n \n",offset));
        out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");return out.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
