package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.*;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeAccessApplicationApi;
import com.spaceagent.platform.knowledge.application.KnowledgeEmbeddingStage;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.memory.*;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeEmbeddingStageTest {
    InMemoryKnowledgeIndexJobRepository jobs;
    InMemoryKnowledgeIndexCatalogRepository catalog;
    InMemoryKnowledgeIndexObjectStore outputs;
    KnowledgeAccessApplicationApi access;
    IdentityApplicationApi identity;
    IdentityActivityApplicationApi activity;
    EmbeddingBatchApplicationApi inference;
    KnowledgeIndexJob job;
    @BeforeEach void setup() {
        jobs=spy(new InMemoryKnowledgeIndexJobRepository()); catalog=new InMemoryKnowledgeIndexCatalogRepository(); outputs=new InMemoryKnowledgeIndexObjectStore();
        access=mock(KnowledgeAccessApplicationApi.class); identity=mock(IdentityApplicationApi.class); activity=mock(IdentityActivityApplicationApi.class);
        inference=mock(EmbeddingBatchApplicationApi.class); var now=Instant.now();
        when(activity.isUserActive("owner")).thenReturn(true);
        when(identity.findTenant("org")).thenReturn(Optional.of(new TenantView("org","Org","org",TenantStatus.ACTIVE,now)));
        when(identity.findTenantMembership("org","owner")).thenReturn(Optional.of(new TenantMembershipView("org","owner",TenantRole.OWNER,TenantMembershipStatus.ACTIVE,now,now)));
        var space=new KnowledgeEmbeddingSpace("space","base","provider","embedding","r1","a".repeat(64),2,"b".repeat(64),"c".repeat(64),"owner",now);
        catalog.insertSpaceIfAbsent(space);
        catalog.insertGenerationIfAbsent(new KnowledgeIndexGeneration("gen","base","doc",space.id(),"a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"owner",now));
        String id=UUID.randomUUID().toString();
        jobs.enqueue(KnowledgeIndexJob.queued(id,new Input("base","gen","owner","org","key","a".repeat(64),"dense_v1",5),now));
        job=jobs.claimNext("worker",90).orElseThrow();
        for(Stage s:List.of(Stage.PARSING,Stage.CHUNKING)) {
            jobs.planStage(job.lease(),new Manifest(id,s,"a".repeat(64),1)); jobs.beginBatch(job.lease(),s,0,"a".repeat(64),1);
            jobs.completeBatch(job.lease(),new Batch(id,s,0,"a".repeat(64),1,BatchState.COMPLETED,"knowledge-index/"+id+"/fixture","b".repeat(64)));
            job=jobs.advance(job.lease(),s);
        }
        when(inference.embed(any())).thenAnswer(a->{EmbeddingBatchApplicationApi.Request r=a.getArgument(0);
            return new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.SUCCEEDED,
                    r.inputs().stream().map(t->List.of(1.0,0.5)).toList(),3L,null,null);});
    }
    KnowledgeEmbeddingStage stage() {return new KnowledgeEmbeddingStage(jobs,catalog,access,identity,activity,inference,outputs,new ObjectMapper());}
    List<KnowledgeEmbeddingStage.Chunk> chunks(int count,String text) {return IntStream.range(0,count).mapToObj(i->new KnowledgeEmbeddingStage.Chunk("chunk-"+i,text+i)).toList();}
    @Test void splitsByCountAndPersistsReusableAlignedOutputsWithoutRebuyingEmbeddings() {
        var inputs=chunks(65,"private");
        doThrow(new IllegalStateException("crash before advancing")).doCallRealMethod().when(jobs).advance(any(),eq(Stage.EMBEDDING));
        assertThatThrownBy(()->stage().execute(job.lease(),inputs)).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.batches(job.id(),Stage.EMBEDDING,0,100)).hasSize(2).allMatch(b->b.state()==BatchState.COMPLETED);
        var resumed=stage().execute(job.lease(),inputs);
        assertThat(resumed.progress().stage()).isEqualTo(Stage.WRITING);
        verify(inference,times(2)).embed(any());
        var first=jobs.batches(job.id(),Stage.EMBEDDING,0,1).getFirst();
        assertThat(first.itemCount()).isEqualTo(64);
        assertThat(new String(outputs.read(first.outputReference(),first.outputHash()),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("private");
    }
    @Test void tokenBoundAlsoSplitsAndUnknownStopsFurtherBatches() {
        doReturn(new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.UNKNOWN,List.of(),null,null,"UNKNOWN")).when(inference).embed(any());
        var result=stage().execute(job.lease(),chunks(5,"x".repeat(7900)));
        assertThat(jobs.manifest(job.id(),Stage.EMBEDDING).orElseThrow().batchCount()).isEqualTo(2);
        assertThat(result.progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThat(jobs.batches(job.id(),Stage.EMBEDDING,0,100)).hasSize(1);
        verify(inference,times(1)).embed(any());
    }
    @Test void modelSpecificTenItemPolicySplitsLargeDocumentsBeforeDispatch() {
        List<List<KnowledgeEmbeddingStage.Chunk>> batches=org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                KnowledgeEmbeddingStage.class,"split",chunks(64,"text"),1024,10);
        assertThat(batches).hasSize(7);
        assertThat(batches).allSatisfy(batch->assertThat(batch.size()).isLessThanOrEqualTo(10));
        assertThat(EmbeddingBatchApplicationApi.modelCapabilities("text-embedding-v4").dimensionsParameterSupported()).isTrue();
    }
    @Test void explicitRejectionCanBeRetriedUnderANewBoundedAttemptIdentity() {
        doReturn(new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.REJECTED,List.of(),null,null,"REJECTED")).when(inference).embed(any());
        var failed=stage().execute(job.lease(),chunks(1,"text"));
        assertThat(failed.progress().state()).isEqualTo(State.FAILED);
        assertThat(jobs.batches(job.id(),Stage.EMBEDDING,0,1).getFirst().state()).isEqualTo(BatchState.REJECTED);
        jobs.retry(job.id(),failed.progress().revision()); job=jobs.claimNext("worker",90).orElseThrow();
        stage().execute(job.lease(),chunks(1,"text"));
        var arguments=org.mockito.ArgumentCaptor.forClass(EmbeddingBatchApplicationApi.Request.class);
        verify(inference,times(2)).embed(arguments.capture());
        assertThat(arguments.getAllValues().get(0).operationKey()).endsWith("attempt:1");
        assertThat(arguments.getAllValues().get(1).operationKey()).endsWith("attempt:2");
    }
    @Test void revokedActorDoesNotDispatchAndCancelledJobCannotAcceptLateResults() {
        when(activity.isUserActive("owner")).thenReturn(false);
        assertThatThrownBy(()->stage().execute(job.lease(),chunks(1,"text"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inference);
        when(activity.isUserActive("owner")).thenReturn(true);
        doAnswer(a->{
            var current=jobs.find(job.id()).orElseThrow(); jobs.cancel(job.id(),current.progress().revision());
            return new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.SUCCEEDED,List.of(List.of(1.0,0.5)),1L,null,null);
        }).when(inference).embed(any());
        assertThatThrownBy(()->stage().execute(job.lease(),chunks(1,"text"))).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.find(job.id()).orElseThrow().progress().state()).isEqualTo(State.CANCELLED);
    }
}
