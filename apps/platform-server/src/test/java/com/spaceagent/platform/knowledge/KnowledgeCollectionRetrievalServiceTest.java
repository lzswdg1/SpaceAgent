package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.application.KnowledgeCollectionRetrievalService;
import com.spaceagent.platform.knowledge.infrastructure.LangChainKnowledgeDocumentChunker;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.*;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeCollectionRetrievalServiceTest {
    final KnowledgeAccessApplicationApi access=mock(KnowledgeAccessApplicationApi.class);
    final KnowledgeRetrievalIndexRepository repository=mock(KnowledgeRetrievalIndexRepository.class);
    final KnowledgeIndexCatalogRepository catalog=mock(KnowledgeIndexCatalogRepository.class);
    final VectorIndexGateway vectors=mock(VectorIndexGateway.class);
    final EmbeddingBatchApplicationApi embeddings=mock(EmbeddingBatchApplicationApi.class);
    final IdentityApplicationApi identity=mock(IdentityApplicationApi.class);
    final IdentityActivityApplicationApi activity=mock(IdentityActivityApplicationApi.class);
    final KnowledgeBaseApplicationApi.Actor actor=new KnowledgeBaseApplicationApi.Actor("reader","org");
    KnowledgeCollectionRetrievalService service;
    @BeforeEach void setup(){
        ObjectProvider<VectorIndexGateway> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(vectors);
        service=new KnowledgeCollectionRetrievalService(access,repository,catalog,provider,embeddings,new LangChainKnowledgeDocumentChunker(),activity,identity);
        var now=Instant.now();when(activity.isUserActive("reader")).thenReturn(true);
        when(identity.findTenant("org")).thenReturn(Optional.of(new TenantView("org","org","org",TenantStatus.ACTIVE,now)));
        when(identity.findTenantMembership("org","reader")).thenReturn(Optional.of(new TenantMembershipView("org","reader",TenantRole.MEMBER,TenantMembershipStatus.ACTIVE,now,now)));
        when(access.authorize(actor,"base",KnowledgeBase.Permission.READ)).thenReturn(new KnowledgeBaseApplicationApi.BaseView(
            new KnowledgeBase("base",KnowledgeBase.Scope.ORGANIZATION,"org","creator","Knowledge",null,KnowledgeBase.State.ACTIVE,1,now,now),KnowledgeBase.Permission.READ));
        when(repository.published("base",257)).thenReturn(List.of(new KnowledgeRetrievalIndexRepository.Published("doc","gen","space","hybrid_v2")));
        when(catalog.findSpace("base","space")).thenReturn(Optional.of(new KnowledgeEmbeddingSpace("space","base","provider","model","r1","a".repeat(64),2,"b".repeat(64),"c".repeat(64),"creator",now)));
        when(embeddings.embed(any())).thenReturn(new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.SUCCEEDED,List.of(List.of(1.0,0.0)),1L,null,null));
        when(vectors.search(any(),any(),anyList(),anyInt())).thenReturn(List.of(match("a"),match("b")));
        when(vectors.lexicalSearch(any(),any(),anyString(),anyInt())).thenReturn(List.of(match("b"),match("a")));
        for(String id:List.of("a","b"))when(repository.evidence("base","gen",id,id.repeat(64))).thenReturn(Optional.of(new KnowledgeRetrievalIndexRepository.Evidence(
            "doc","Title","gen",id,0,"authoritative evidence "+id,id.repeat(64),1,Map.of("page",1))));
        when(repository.evidenceBatch(anyList())).thenAnswer(call -> {
            List<KnowledgeRetrievalIndexRepository.EvidenceKey> keys = call.getArgument(0);
            Map<KnowledgeRetrievalIndexRepository.EvidenceKey, KnowledgeRetrievalIndexRepository.Evidence> values = new LinkedHashMap<>();
            for (var key : keys) repository.evidence(key.baseId(),key.generationId(),key.chunkId(),key.contentHash()).ifPresent(e -> values.put(key,e));
            return values;
        });
    }
    static VectorIndexGateway.Match match(String id){return new VectorIndexGateway.Match("doc","gen",id,id.repeat(64),.9);}
    KnowledgeCollectionRetrievalApi.Query query(int tokens){return new KnowledgeCollectionRetrievalApi.Query(actor,List.of("base"),"question",5,tokens,"logical-call");}
    @Test void fusedHitsUsePostgresEvidenceAndStableKeysWithoutCreatorImpersonation(){
        var result=service.retrieve(query(512));assertThat(result.hits()).hasSize(2);assertThat(result.degraded()).isFalse();
        assertThat(result.hits().getFirst().citation().location()).containsEntry("page",1);
        assertThat(result.hits().getFirst().rankScore()).isCloseTo(1.0/61+1.0/62,within(1e-9));
        verify(embeddings).embed(argThat(r->r.actorId().equals("reader") && r.operationKey().equals("rag-query:logical-call:space")));
        verify(repository,times(2)).evidenceBatch(anyList());
    }
    @Test void rejectedBindingAndInactiveActorNeverCallAModel(){
        when(access.authorize(actor,"base",KnowledgeBase.Permission.READ)).thenThrow(new BusinessException("denied",HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->service.retrieve(query(512))).isInstanceOf(BusinessException.class);verifyNoInteractions(embeddings,vectors);
        when(activity.isUserActive("reader")).thenReturn(false);assertThatThrownBy(()->service.retrieve(query(512))).isInstanceOf(BusinessException.class);
    }
    @Test void staleGenerationAndForeignRecallAreDiscarded(){
        when(repository.evidence("base","gen","a","a".repeat(64))).thenReturn(Optional.empty());
        when(vectors.lexicalSearch(any(),any(),anyString(),anyInt())).thenReturn(List.of(new VectorIndexGateway.Match("secret","foreign","x","f".repeat(64),1)));
        var result=service.retrieve(query(512));assertThat(result.hits()).hasSize(1);assertThat(result.partial()).isTrue();
        verify(repository,never()).evidence(anyString(),eq("foreign"),anyString(),anyString());
    }
    @Test void unknownEmbeddingUsesLexicalWithoutAnotherPurchaseAndClearlyDegrades(){
        when(embeddings.embed(any())).thenReturn(new EmbeddingBatchApplicationApi.Result("call",EmbeddingBatchApplicationApi.Status.UNKNOWN,List.of(),null,null,"TIMEOUT"));
        var result=service.retrieve(query(512));assertThat(result.hits()).hasSize(2);assertThat(result.degraded()).isTrue();
        assertThat(result.warnings()).contains("QUERY_EMBEDDING_UNKNOWN");verify(vectors,never()).search(any(),any(),anyList(),anyInt());verify(embeddings,times(1)).embed(any());
    }
    @Test void revocationDuringRecallInvalidatesResponse(){
        when(vectors.lexicalSearch(any(),any(),anyString(),anyInt())).thenAnswer(a->{when(access.authorize(actor,"base",KnowledgeBase.Permission.READ)).thenThrow(new BusinessException("revoked",HttpStatus.NOT_FOUND));return List.of(match("a"));});
        assertThatThrownBy(()->service.retrieve(query(512))).isInstanceOf(BusinessException.class);
    }
    @Test void contextBudgetNeverSplitsCitationIntoFabricatedOffsets(){
        when(repository.evidence("base","gen","a","a".repeat(64))).thenReturn(Optional.of(new KnowledgeRetrievalIndexRepository.Evidence("doc","Title","gen","a",0,"long evidence ".repeat(500),"a".repeat(64),1,Map.of("page",1))));
        var result=service.retrieve(query(64));assertThat(result.contextTokens()).isLessThanOrEqualTo(64);assertThat(result.partial()).isTrue();assertThat(result.warnings()).contains("CONTEXT_BUDGET_REACHED");
    }
    @Test void rerankReceivesOnlyCurrentlyAuthorizedEvidenceAndFailureFallsBack(){
        var reranker=mock(com.spaceagent.platform.inference.api.RerankApplicationApi.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"reranker",reranker);
        when(repository.evidence("base","gen","a","a".repeat(64))).thenReturn(Optional.empty());
        when(reranker.rank(any())).thenReturn(new com.spaceagent.platform.inference.api.RerankApplicationApi.Result(false,List.of(),"model","revision","UNAVAILABLE"));
        var result=service.retrieve(query(512));assertThat(result.hits()).hasSize(1);assertThat(result.warnings()).contains("RERANK_FALLBACK");
        verify(reranker).rank(argThat(r->r.actorId().equals("reader") && r.texts().equals(List.of("authoritative evidence b"))));
    }
    @Test void rerankOrderingIsAppliedButCitationsAreRecheckedAfterCompute(){
        var reranker=mock(com.spaceagent.platform.inference.api.RerankApplicationApi.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"reranker",reranker);
        when(reranker.rank(any())).thenAnswer(a->{when(repository.evidence("base","gen","a","a".repeat(64))).thenReturn(Optional.empty());
            return new com.spaceagent.platform.inference.api.RerankApplicationApi.Result(true,List.of(new com.spaceagent.platform.inference.api.RerankApplicationApi.Score(1,.9),new com.spaceagent.platform.inference.api.RerankApplicationApi.Score(0,.1)),"model","revision",null);});
        var result=service.retrieve(query(512));assertThat(result.hits()).hasSize(1);assertThat(result.hits().getFirst().citation().chunkId()).isEqualTo("b");assertThat(result.ranking()).isEqualTo("RERANK");
    }
}
