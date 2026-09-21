package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi.*;
import com.spaceagent.platform.inference.application.EmbeddingBatchApplicationService;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.memory.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingBatchApplicationServiceTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final ModelProviderSecretCipher CIPHER=new ModelProviderSecretCipher() {
        public String encrypt(String value) {return "fixture:"+Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public String decrypt(String value) {return new String(Base64.getDecoder().decode(value.substring(8)),java.nio.charset.StandardCharsets.UTF_8);}
    };
    InferenceProviderRepository providers;
    ModelPriceRepository prices;
    InMemoryEmbeddingCallRepository calls;
    InMemoryInferenceBudgetRepository budgets;
    EmbeddingBatchExecutor executor;
    ModelProvider provider;
    @BeforeEach void setup() {
        providers=mock(InferenceProviderRepository.class); prices=mock(ModelPriceRepository.class); executor=mock(EmbeddingBatchExecutor.class);
        calls=new InMemoryEmbeddingCallRepository(Instant::now); budgets=spy(new InMemoryInferenceBudgetRepository(Instant::now));
        provider=new ModelProvider("provider","org","owner","embed","openai-compatible","https://model.example/v1",CIPHER.encrypt("secret"),
                "bearer",true,false,Instant.now(),Instant.now());
        when(providers.findProviderByTenantAndId("org","provider")).thenReturn(Optional.of(provider));
        when(providers.findModelsByProviderId("provider")).thenReturn(List.of(new ProviderModel("model-row","provider","embed-model","Embedding",8192,false,Instant.now())));
        when(prices.findEffective(eq("model-row"),any())).thenReturn(Optional.of(new ModelPrice("price","org","model-row",1,1_000_000,0,"USD",Instant.EPOCH,null,"owner",Instant.now())));
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenReturn(new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,List.of(List.of(1.0,0.5)),3L,null));
    }
    EmbeddingBatchApplicationService service() { return new EmbeddingBatchApplicationService(providers,v->v,calls,budgets,prices,executor,CIPHER,JSON,new UuidGenerator(),Instant::now); }
    Request request(String key) { return new Request("org","owner",key,"provider","embed-model",fingerprint(provider),"a".repeat(64),2,List.of("private evidence")); }
    static String fingerprint(ModelProvider p) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(List.of(p.id(),p.providerType(),p.baseUrl(),p.authType()))));}
        catch(Exception e) {throw new AssertionError(e);}
    }
    @Test void successIsEncryptedReplayedAndAccountedExactlyOnceWithPinnedPrice() {
        var first=service().embed(request("one")); var second=service().embed(request("one"));
        assertThat(first.status()).isEqualTo(Status.SUCCEEDED); assertThat(second).isEqualTo(first);
        assertThat(first.inputTokens()).isEqualTo(3); assertThat(first.costMicros()).isEqualTo(3);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
        assertThat(calls.find(first.callId()).orElseThrow().encryptedResponse()).doesNotContain("[[1.0", "private evidence");
        assertThat(budgets.usage("org").consumedRequests()).isEqualTo(1);
        assertThat(budgets.usage("org").reservedRequests()).isZero();
        assertThat(budgets.usage("org").consumedCostMicros()).isEqualTo(3);
    }
    @Test void organizationPoolDelegatesOnlyTheExactEnabledModelAndRevocationStopsUse(){
        var service=service();var pools=mock(ModelPoolRepository.class);service.modelPools(pools);var now=Instant.now();
        var shared=new ModelPool("pool","org","owner","Shared embedding",ModelPoolVisibility.ORGANIZATION,ModelPoolRoutingStrategy.PRIORITY,false,ModelPoolStatus.ACTIVE,now,now);
        when(pools.findPoolsByTenantId("org")).thenReturn(List.of(shared));
        when(pools.findMembersByPoolId("pool")).thenReturn(List.of(new ModelPoolMember("member","pool","provider","model-row",1,1,true,now,now)));
        var model=providers.findModelsByProviderId("provider").getFirst();
        when(providers.findModelById("model-row")).thenReturn(Optional.of(model));
        var own=request("shared");var r=new Request("org","reader",own.operationKey(),own.providerId(),own.modelId(),own.providerFingerprint(),own.spaceFingerprint(),2,own.inputs());
        assertThat(service.embed(r).status()).isEqualTo(Status.SUCCEEDED);
        when(pools.findPoolsByTenantId("org")).thenReturn(List.of(new ModelPool("pool","org","other-member","Unauthorized delegation",ModelPoolVisibility.ORGANIZATION,ModelPoolRoutingStrategy.PRIORITY,false,ModelPoolStatus.ACTIVE,now,now)));
        assertThatThrownBy(()->service.embed(r)).isInstanceOf(BusinessException.class);
        when(pools.findPoolsByTenantId("org")).thenReturn(List.of(shared.disable(now)));
        assertThatThrownBy(()->service.embed(r)).isInstanceOf(BusinessException.class);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
    }
    @Test void changedPayloadAndWrongOwnerOrOrganizationNeverDispatch() {
        var first=request("one"); service().embed(first);
        var changed=new Request(first.tenantId(),first.actorId(),first.operationKey(),first.providerId(),first.modelId(),first.providerFingerprint(),first.spaceFingerprint(),2,List.of("different text"));
        assertThatThrownBy(()->service().embed(changed)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EMBEDDING_IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(()->service().embed(new Request("org","intruder","two","provider","embed-model",first.providerFingerprint(),first.spaceFingerprint(),2,first.inputs()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service().embed(new Request("other-org","owner","two","provider","embed-model",first.providerFingerprint(),first.spaceFingerprint(),2,first.inputs()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service().embed(new Request("org","owner","two","provider","embed-model","b".repeat(64),first.spaceFingerprint(),2,first.inputs()))).isInstanceOf(BusinessException.class);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
    }
    @Test void budgetRejectsBeforePostAndDoesNotCreateFakeUsage() {
        budgets.savePolicy(new InferenceBudgetPolicy("org",true,1,10,1000,1,"owner",Instant.now(),Instant.now()));
        var result=service().embed(request("limited"));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        verifyNoInteractions(executor);
        assertThat(budgets.usage("org").consumedRequests()).isZero();
    }
    @Test void unknownAndMalformedVectorsRetainReservationAndNeverRepeatPost() {
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenThrow(new RuntimeException("remote secret must not escape"));
        var result=service().embed(request("timeout"));
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(service().embed(request("timeout")).status()).isEqualTo(Status.UNKNOWN);
        assertThat(budgets.findEmbeddingReservation(result.callId()).orElseThrow().status()).isEqualTo("UNKNOWN");
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
        doReturn(new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,List.of(List.of(Double.NaN,1.0)),3L,null))
                .when(executor).execute(any(),anyString(),anyInt(),anyList());
        assertThat(service().embed(request("invalid")).status()).isEqualTo(Status.UNKNOWN);
    }
    @Test void missingUsageIsUnresolvedRatherThanFreeAndDefiniteRejectionReleasesBudget() {
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenReturn(new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,List.of(List.of(1.0,0.5)),null,"EMBEDDING_USAGE_UNREPORTED"));
        var unknownUsage=service().embed(request("unpriced"));
        assertThat(unknownUsage.status()).isEqualTo(Status.SUCCEEDED); assertThat(unknownUsage.costMicros()).isNull();
        assertThat(budgets.findEmbeddingReservation(unknownUsage.callId()).orElseThrow().status()).isEqualTo("UNKNOWN");
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenReturn(new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.REJECTED,List.of(),null,"EMBEDDING_PROVIDER_REJECTED"));
        var rejected=service().embed(request("rejected"));
        assertThat(budgets.findEmbeddingReservation(rejected.callId()).orElseThrow().status()).isEqualTo("RELEASED");
    }
    @Test void duplicateConcurrentRequestDoesNotDispatchAndSettlementCrashCanReplay() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); AtomicInteger count=new AtomicInteger();
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenAnswer(a->{count.incrementAndGet();started.countDown();release.await(5,TimeUnit.SECONDS);
            return new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,List.of(List.of(1.0,0.5)),3L,null);});
        var pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(()->service().embed(request("concurrent"))); assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service().embed(request("concurrent")).status()).isEqualTo(Status.IN_PROGRESS);
            release.countDown(); assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo(Status.SUCCEEDED);
            assertThat(count.get()).isEqualTo(1);
        } finally {release.countDown();pool.shutdownNow();}
        doThrow(new IllegalStateException("settlement interrupted")).doCallRealMethod().when(budgets).settle(anyString(),eq(3L),eq(0L));
        assertThatThrownBy(()->service().embed(request("settlement"))).isInstanceOf(IllegalStateException.class);
        assertThat(service().embed(request("settlement")).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(count.get()).isEqualTo(2);
    }
    @Test void itemTokenAndVectorComponentLimitsRejectBeforeDispatch() {
        assertThat(EmbeddingBounds.inputUpperBound("中文")).isEqualTo(14);
        assertThatThrownBy(()->EmbeddingBounds.validate(List.of("中".repeat(3000)),2,8192)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->EmbeddingBounds.validate(Collections.nCopies(65,"x"),2,8192)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->EmbeddingBounds.validate(Collections.nCopies(9,"x"),32768,8192)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->EmbeddingBounds.validate(Collections.nCopies(5,"x".repeat(8000)),2,8192)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(executor);
    }
    @Test void reconciliationSealsAnUnstartedLogicalKeyAndNeverSendsALateDuplicate() {
        var r=request("paused-before-dispatch");
        assertThat(service().reconcile(r).status()).isEqualTo(Status.REJECTED);
        assertThat(service().embed(r).status()).isEqualTo(Status.REJECTED);
        verifyNoInteractions(executor);
        assertThat(budgets.usage("org").consumedRequests()).isZero();
    }
    @Test void reconciliationReusesConfirmedCacheWithoutProviderCredentialsOrANewRequest() {
        var r=request("confirmed");var first=service().embed(r);
        when(providers.findProviderByTenantAndId(anyString(),anyString())).thenReturn(Optional.empty());
        assertThat(service().reconcile(r)).isEqualTo(first);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
        assertThat(budgets.usage("org").consumedRequests()).isEqualTo(1);
    }
    @Test void erasureRemovesCachedVectorsAndSealsAnyLateCallForThatJob() {
        String job=UUID.randomUUID().toString();var r=request(job+":embedding:0:attempt:1");var first=service().embed(r);
        assertThat(service().eraseJobCache("org","owner",job)).isTrue();
        assertThat(calls.find(first.callId()).orElseThrow().encryptedResponse()).isNull();
        assertThat(service().reconcile(r).safeCode()).isEqualTo("EMBEDDING_OUTPUT_ERASED");
        assertThat(service().embed(request(job+":embedding:1:attempt:1")).status()).isEqualTo(Status.REJECTED);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
    }
}
