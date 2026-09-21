package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway.*;
import com.spaceagent.platform.knowledge.infrastructure.MilvusIndexConfiguration;
import com.spaceagent.platform.knowledge.infrastructure.MilvusVectorIndexGateway;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit local dependency acceptance; a skipped run is never B01B PASS. No model calls. */
@EnabledIfEnvironmentVariable(named="SPACEAGENT_MILVUS_TEST_URI",matches=".+")
class MilvusVectorIndexIntegrationTest {
    @Test void boundedHybridPerformanceSmoke(){
        String uri=System.getenv("SPACEAGENT_MILVUS_TEST_URI");
        var client=new MilvusIndexConfiguration().knowledgeMilvusClient(uri,System.getenv("SPACEAGENT_MILVUS_TEST_TOKEN"),"default",uri.startsWith("http://"));
        var gateway=new MilvusVectorIndexGateway(client,"perf"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        var space=new Space(UUID.randomUUID().toString(),"f".repeat(64),128,"hybrid_v2");var scope=new Scope("org:one","base",List.of("generation"));
        List<Double> query=new ArrayList<>(Collections.nCopies(128,0.0));query.set(0,1.0);
        try{
            gateway.ensureSpace(space);List<Entry> rows=new ArrayList<>();
            for(int i=0;i<256;i++){String text="企业知识库 recovery durable checkpoint evidence "+i;List<Double> vector=new ArrayList<>(Collections.nCopies(128,.001));vector.set(i%128,1.0);
                rows.add(new Entry(i%2==0?"org:one":"org:two","base","doc-"+i,"generation","chunk-"+i,sha(text),vector,text));}
            for(int i=0;i<rows.size();i+=64)gateway.upsertBatch(space,rows.subList(i,i+64));
            List<Long> durations=new ArrayList<>();
            for(int i=0;i<23;i++){long started=System.nanoTime();
                assertThat(gateway.search(space,scope,query,5)).hasSize(5);
                assertThat(gateway.lexicalSearch(space,scope,"知识库 checkpoint",5)).hasSize(5);
                if(i>=3)durations.add((System.nanoTime()-started)/1_000_000);}
            var sorted=durations.stream().sorted().toList();long p50=sorted.get(9),p95=sorted.get(18);
            double seconds=durations.stream().mapToLong(Long::longValue).sum()/1000.0;
            System.out.printf(java.util.Locale.ROOT,"RAG_PERF_FIXTURE rows=256 dimensions=128 queries=20 p50_ms=%d p95_ms=%d pairs_per_second=%.2f%n",p50,p95,20/Math.max(.001,seconds));
            assertThat(p95).isLessThan(5000);
        }finally{try{if(client.hasCollection(HasCollectionReq.builder().collectionName(gateway.collectionName(space)).build()))client.dropCollection(DropCollectionReq.builder().collectionName(gateway.collectionName(space)).build());}finally{client.close();}}
    }
    @Test void actualChineseEnglishBm25KeepsScopeAndDeletesBothIndexes(){
        String uri=System.getenv("SPACEAGENT_MILVUS_TEST_URI");
        var client=new MilvusIndexConfiguration().knowledgeMilvusClient(uri,System.getenv("SPACEAGENT_MILVUS_TEST_TOKEN"),"default",uri.startsWith("http://"));
        var gateway=new MilvusVectorIndexGateway(client,"hybrid"+UUID.randomUUID().toString().replace("-", "").substring(0,10));
        var space=new Space(UUID.randomUUID().toString(),"e".repeat(64),2,"hybrid_v2");
        var scope=new Scope("org:one","base",List.of("gen"));
        String chinese="企业知识库通过权限过滤保护用户隐私。",english="SpaceAgent repository recovery and durable checkpoints.";
        var a=new Entry("org:one","base","zh","gen","a",sha(chinese),List.of(1.0,0.0),chinese);
        var b=new Entry("org:one","base","en","gen","b",sha(english),List.of(0.0,1.0),english);
        var secret=new Entry("org:two","base","private","gen","c",sha(chinese),List.of(1.0,0.0),chinese);
        try{
            gateway.ensureSpace(space);gateway.ensureSpace(space);gateway.upsertBatch(space,List.of(a,b,secret));
            assertThat(gateway.verifyBatch(space,List.of(a,b,secret))).isTrue();
            assertThat(gateway.lexicalSearch(space,scope,"知识库 隐私",10)).extracting(Match::documentId).contains("zh").doesNotContain("private");
            assertThat(gateway.lexicalSearch(space,scope,"repository checkpoints",10)).extracting(Match::documentId).contains("en").doesNotContain("private");
            assertThat(gateway.search(space,scope,List.of(1.0,0.0),10)).extracting(Match::documentId).contains("zh").doesNotContain("private");
            gateway.deleteGeneration(space,scope,"gen");assertThat(gateway.lexicalSearch(space,scope,"知识库 repository",10)).isEmpty();
            assertThat(gateway.generationDeleted(space,scope,"gen")).isTrue();
            assertThat(gateway.lexicalSearch(space,new Scope("org:two","base",List.of("gen")),"知识库",10)).hasSize(1);
        }finally{try{if(client.hasCollection(HasCollectionReq.builder().collectionName(gateway.collectionName(space)).build()))client.dropCollection(DropCollectionReq.builder().collectionName(gateway.collectionName(space)).build());}finally{client.close();}}
    }
    private static String sha(String value){return com.spaceagent.platform.knowledge.application.KnowledgeIndexBuildCoordinator.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void actualMilvusRoundTripIsolationSchemaAndDeletion() {
        String uri=System.getenv("SPACEAGENT_MILVUS_TEST_URI");
        String token=System.getenv("SPACEAGENT_MILVUS_TEST_TOKEN");
        String prefix="test"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        var config=new MilvusIndexConfiguration();
        var client=config.knowledgeMilvusClient(uri,token,"default",uri.startsWith("http://"));
        var gateway=new MilvusVectorIndexGateway(client,prefix);
        Space space=new Space(UUID.randomUUID().toString(),"a".repeat(64),2);
        Scope first=new Scope("org:one","base",List.of("gen"));
        Scope second=new Scope("org:two","base",List.of("gen"));
        Scope otherGeneration=new Scope("org:one","base",List.of("gen-two"));
        var a=new Entry("org:one","base","doc-one","gen","chunk","b".repeat(64),List.of(1.0,0.0));
        var b=new Entry("org:two","base","doc-two","gen","chunk","c".repeat(64),List.of(1.0,0.0));
        var c=new Entry("org:one","base","doc-three","gen-two","chunk","d".repeat(64),List.of(1.0,0.0));
        try {
            gateway.ensureSpace(space);
            gateway.ensureSpace(space);
            gateway.upsertBatch(space,List.of(a,b,c));
            gateway.upsertBatch(space,List.of(a));
            assertThat(gateway.verifyBatch(space,List.of(a,b,c))).isTrue();
            assertThat(gateway.search(space,first,List.of(1.0,0.0),5)).extracting(Match::documentId).containsExactly("doc-one");
            assertThat(gateway.search(space,second,List.of(1.0,0.0),5)).extracting(Match::documentId).containsExactly("doc-two");
            assertThat(gateway.search(space,otherGeneration,List.of(1.0,0.0),5)).extracting(Match::documentId).containsExactly("doc-three");
            assertThatThrownBy(() -> {
                var unauthorized=config.knowledgeMilvusClient(uri,"untrusted:wrong-fixture-password","default",uri.startsWith("http://"));
                try { unauthorized.hasCollection(HasCollectionReq.builder().collectionName(gateway.collectionName(space)).build()); }
                finally { unauthorized.close(); }
            }).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->gateway.ensureSpace(new Space(space.id(),space.fingerprint(),3)))
                    .isInstanceOf(IllegalStateException.class);
            gateway.deleteGeneration(space,first,"gen");
            assertThat(gateway.generationDeleted(space,first,"gen")).isTrue();
            assertThat(gateway.generationDeleted(space,second,"gen")).isFalse();
            assertThat(gateway.generationDeleted(space,otherGeneration,"gen-two")).isFalse();
        } finally {
            try {
                // The random collection can exist even if ensureSpace failed during load or schema verification.
                if(client.hasCollection(HasCollectionReq.builder().collectionName(gateway.collectionName(space)).build()))
                    client.dropCollection(DropCollectionReq.builder().collectionName(gateway.collectionName(space)).build());
            } finally {
                client.close();
            }
        }
    }
}
