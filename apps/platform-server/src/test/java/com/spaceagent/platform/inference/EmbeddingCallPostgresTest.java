package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi.*;
import com.spaceagent.platform.inference.application.EmbeddingBatchApplicationService;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.persistence.*;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class EmbeddingCallPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("embedding_calls").withUsername("fixture").withPassword("fixture-password");
    static DriverManagerDataSource ds; static JdbcTemplate jdbc;
    static ModelProvider provider;
    PostgresEmbeddingCallRepository calls;
    PostgresInferenceBudgetRepository budgets;
    @BeforeAll static void migrate() {
        ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1088").load().migrate();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('org','org','org','ACTIVE',now(),now())");
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES ('owner','org','owner@test.local','Owner',now(),now())");
        var migration=Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(10);assertThat(migration.migrate().migrationsExecuted).isZero();
        provider=new ModelProvider("provider","org","owner","embedding","openai-compatible","https://embedding.example/v1",
                EmbeddingBatchApplicationServiceTest.CIPHER.encrypt("fixture-secret"),"bearer",true,false,Instant.now(),Instant.now());
        var providers=new PostgresInferenceProviderRepository(jdbc);providers.saveProvider(provider);
        providers.saveModel(new ProviderModel("model-row","provider","embedding","embedding",8192,false,Instant.now()));
        new PostgresModelPriceRepository(jdbc).save(new ModelPrice(UUID.randomUUID().toString(),"org","model-row",1,1_000_000,0,"USD",Instant.EPOCH,null,"owner",Instant.now()));
    }
    @BeforeEach void setup() {
        // Exclusive disposable fixture database; no production/local user data is read or changed.
        jdbc.update("DELETE FROM platform_inference_budget_reservations");jdbc.update("DELETE FROM platform_inference_budget_periods");
        jdbc.update("DELETE FROM platform_inference_budget_policies");jdbc.update("DELETE FROM platform_embedding_calls");
        jdbc.update("DELETE FROM platform_embedding_output_tombstones");
        calls=new PostgresEmbeddingCallRepository(jdbc);budgets=new PostgresInferenceBudgetRepository(jdbc,new DataSourceTransactionManager(ds));
    }
    EmbeddingBatchApplicationService service(EmbeddingBatchExecutor executor) {
        return new EmbeddingBatchApplicationService(new PostgresInferenceProviderRepository(jdbc),v->v,
                new PostgresEmbeddingCallRepository(new JdbcTemplate(ds)),budgets,new PostgresModelPriceRepository(jdbc),executor,
                EmbeddingBatchApplicationServiceTest.CIPHER,EmbeddingBatchApplicationServiceTest.JSON,new UuidGenerator(),Instant::now);
    }
    Request request() {return new Request("org","owner","fixture-key","provider","embedding",EmbeddingBatchApplicationServiceTest.fingerprint(provider),"a".repeat(64),2,List.of("private fixture"));}
    EmbeddingCall candidate(String id) {var now=Instant.now();return new EmbeddingCall(id,"org","owner","claim-key","a".repeat(64),"provider","embedding",2,
            null,null,EmbeddingCall.State.PREPARED,null,null,null,null,now.plusSeconds(120),now);}
    @Test void actualBudgetAndEncryptedOutcomeSurviveRestartWithoutAnAgentRun() {
        var executor=mock(EmbeddingBatchExecutor.class);
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenReturn(new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,List.of(List.of(1.0,0.5)),7L,null));
        var result=service(executor).embed(request());
        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);assertThat(result.costMicros()).isEqualTo(7);
        assertThat(service(executor).embed(request())).isEqualTo(result);
        verify(executor,times(1)).execute(any(),anyString(),anyInt(),anyList());
        var reservation=jdbc.queryForMap("SELECT agent_run_id,embedding_call_id,status FROM platform_inference_budget_reservations");
        assertThat(reservation).containsEntry("agent_run_id",null).containsEntry("embedding_call_id",result.callId()).containsEntry("status","SETTLED");
        assertThat(jdbc.queryForObject("SELECT encrypted_response FROM platform_embedding_calls",String.class)).doesNotContain("private fixture","[[1.0");
        assertThat(budgets.usage("org").consumedCostMicros()).isEqualTo(7);
    }
    @Test void independentClaimsAreAtomicAndExpiredDispatchNeverReexecutes() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return new PostgresEmbeddingCallRepository(new JdbcTemplate(ds)).claim(candidate(UUID.randomUUID().toString()));});
            var b=pool.submit(()->{start.await();return new PostgresEmbeddingCallRepository(new JdbcTemplate(ds)).claim(candidate(UUID.randomUUID().toString()));});
            start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.created()).isNotEqualTo(second.created());assertThat(first.call().id()).isEqualTo(second.call().id());
            assertThat(calls.dispatch(first.call().id())).isTrue();
            jdbc.update("UPDATE platform_embedding_calls SET deadline=clock_timestamp()-interval '1 second'");
            var recovered=new PostgresEmbeddingCallRepository(new JdbcTemplate(ds)).claim(candidate(UUID.randomUUID().toString()));
            assertThat(recovered.created()).isFalse();assertThat(recovered.call().state()).isEqualTo(EmbeddingCall.State.UNKNOWN);
            assertThat(calls.dispatch(first.call().id())).isFalse();
            assertThat(calls.finish(first.call().id(),EmbeddingCall.State.DISPATCHED,EmbeddingCall.State.SUCCEEDED,"late",1L,null,null)).isFalse();
        } finally {pool.shutdownNow();}
    }
    @Test void unknownKeepsBudgetAndBlocksPrematureCleanup() {
        EmbeddingBatchExecutor executor=(p,m,d,i)->new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.UNKNOWN,List.of(),null,"EMBEDDING_TIMEOUT");
        var result=service(executor).embed(request());
        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(budgets.findEmbeddingReservation(result.callId()).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThat(budgets.usage("org").reservedRequests()).isEqualTo(1);
        assertThatThrownBy(()->new PostgresInferenceCleanupService(jdbc).cleanupOrganization("org"))
                .isInstanceOf(IllegalStateException.class).hasMessage("EMBEDDING_RECONCILIATION_REQUIRED");
        assertThat(calls.find(result.callId())).isPresent();
    }
    @Test void interruptedSettlementIsRepairableAndSubjectConstraintPreventsFakeRunReservations() {
        var call=calls.claim(candidate(UUID.randomUUID().toString())).call();calls.dispatch(call.id());
        budgets.reserve(new InferenceBudgetRepository.ReserveRequest(UUID.randomUUID().toString(),"org",null,call.id(),"provider","embedding",
                null,null,null,20,null,call.id()));
        assertThat(calls.finish(call.id(),EmbeddingCall.State.DISPATCHED,EmbeddingCall.State.SUCCEEDED,"fixture-output",4L,null,null)).isTrue();
        // Outcome was committed before settlement: a reconstructed accounting adapter can settle it exactly once.
        var restart=new PostgresInferenceBudgetRepository(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
        var reservation=restart.findEmbeddingReservation(call.id()).orElseThrow();
        restart.settle(reservation.id(),4,0);restart.settle(reservation.id(),4,0);
        assertThat(restart.usage("org").consumedRequests()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE platform_inference_budget_reservations SET embedding_call_id=NULL"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('other','other','other','ACTIVE',now(),now())");
        assertThatThrownBy(()->jdbc.update("UPDATE platform_inference_budget_reservations SET tenant_id='other'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void erasureTombstonePreventsLateCompletionFromRecreatingSensitiveResponse() {
        String prefix="claim-";var call=calls.claim(candidate(UUID.randomUUID().toString())).call();assertThat(calls.dispatch(call.id())).isTrue();
        calls.eraseOutputs("org","owner",prefix);
        assertThat(calls.finish(call.id(),EmbeddingCall.State.DISPATCHED,EmbeddingCall.State.SUCCEEDED,"late-sensitive-response",7L,null,null)).isTrue();
        assertThat(calls.find(call.id()).orElseThrow().encryptedResponse()).isNull();
        assertThat(calls.find(call.id()).orElseThrow().inputTokens()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT response_erased FROM platform_embedding_calls WHERE id=?",Boolean.class,call.id())).isTrue();
    }
}
