package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeIndexJobPostgresTest extends KnowledgeIndexJobRepositoryContract {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("index_jobs").withUsername("fixture").withPassword("fixture-password");
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    @BeforeAll static void migrate() {
        ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1087").load().migrate();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('org','org','org','ACTIVE',now(),now())");
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES ('owner','org','owner@test.local','Owner',now(),now())");
        jdbc.update("INSERT INTO platform_knowledge_bases(id,scope,owner_id,name,description,state,revision,created_at,updated_at) VALUES ('base','PERSONAL','owner','base','','ACTIVE',1,now(),now())");
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @BeforeEach void setup() {
        // This database belongs exclusively to this disposable test container.
        jdbc.update("DELETE FROM platform_knowledge_index_batches"); jdbc.update("DELETE FROM platform_knowledge_index_manifests");
        jdbc.update("DELETE FROM platform_knowledge_index_outbox"); jdbc.update("DELETE FROM platform_knowledge_index_jobs");
        repo=restart();
    }
    protected void expire(String id) { jdbc.update("UPDATE platform_knowledge_index_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    protected void due(String id) { jdbc.update("UPDATE platform_knowledge_index_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    protected KnowledgeIndexJobRepository restart() { return new PostgresKnowledgeIndexJobRepository(new JdbcTemplate(ds)); }
    protected void seed(Input i) {
        jdbc.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES (?,'owner','doc','text/plain','inline:test','READY',now(),now())",i.generationId());
        var catalog=new PostgresKnowledgeIndexCatalogRepository(jdbc); var now=Instant.now();
        catalog.insertScopeIfAbsent(new KnowledgeDocumentScope(i.generationId(),i.baseId(),"owner","EXPLICIT",now));
        var space=catalog.insertSpaceIfAbsent(new KnowledgeEmbeddingSpace("space","base","provider","model","r1","a".repeat(64),2,"b".repeat(64),"c".repeat(64),"owner",now));
        catalog.insertGenerationIfAbsent(new KnowledgeIndexGeneration(i.generationId(),i.baseId(),i.generationId(),space.id(),
                "a".repeat(64),"b".repeat(64),"c".repeat(64),java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(i.generationId().getBytes(java.nio.charset.StandardCharsets.US_ASCII),32)),"owner",now));
    }
    @Test void outboxAndJobInsertRollbackTogetherAndGenerationCannotRunTwice() {
        var candidate=candidate(5);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{repo.enqueue(candidate);s.setRollbackOnly();return null;});
        assertThat(repo.find(candidate.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_outbox",Integer.class)).isZero();
        var actual=repo.enqueue(candidate); repo.enqueue(candidate);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_outbox",Integer.class)).isEqualTo(1);
        var i=candidate.input();
        assertThatThrownBy(()->repo.enqueue(KnowledgeIndexJob.queued(java.util.UUID.randomUUID().toString(),new Input(i.baseId(),i.generationId(),
                i.requestedBy(),i.organizationId(),"another-key",i.requestHash(),i.storageSchema(),5),Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        repo.cancel(actual.id(),actual.progress().revision());
        assertThat(jdbc.queryForObject("SELECT pending FROM platform_knowledge_index_outbox WHERE job_id=?",Boolean.class,actual.id())).isFalse();
    }
    @Test void twoIndependentWorkersCompeteAndOnlyOneClaimsTheGeneration() throws Exception {
        repo.enqueue(candidate(5));
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(pool.submit(()->{start.await();return restart().claimNext("worker-1",60);}),
                    pool.submit(()->{start.await();return restart().claimNext("worker-2",60);}));
            start.countDown(); int claimed=0;
            for(var f:futures) if(f.get(10,TimeUnit.SECONDS).isPresent()) claimed++;
            assertThat(claimed).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT deliveries FROM platform_knowledge_index_outbox",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void tenantFairnessPreventsOneTenantFromOccupyingBothWorkers(){
        var first=repo.enqueue(candidate(5));repo.enqueue(candidate(5));
        var third=candidate(5);var i=third.input();
        repo.enqueue(KnowledgeIndexJob.queued(third.id(),new Input(i.baseId(),i.generationId(),i.requestedBy(),"second-tenant",i.idempotencyKey(),i.requestHash(),i.storageSchema(),5),Instant.now()));
        var a=repo.claimNext("worker-1",60).orElseThrow();var b=restart().claimNext("worker-2",60).orElseThrow();
        assertThat(a.input().organizationId()).isNotEqualTo(b.input().organizationId());assertThat(repo.claimNext("worker-3",60)).isEmpty();
    }
    @Test void queryConcurrencyUsesDatabaseLeasesAndAdmissionQuotasAreAtomic(){
        var ops=new PostgresKnowledgeOperationalRepository(jdbc);String tenant=java.util.UUID.randomUUID().toString();
        var first=ops.acquireQuery(tenant,1).orElseThrow();assertThat(ops.acquireQuery(tenant,1)).isEmpty();ops.releaseQuery(first);
        var expired=ops.acquireQuery(tenant,1).orElseThrow();jdbc.update("UPDATE platform_knowledge_query_leases SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",expired);
        assertThat(ops.acquireQuery(tenant,1)).isPresent();
        repo.enqueue(candidate(5));assertThatThrownBy(()->ops.admit("base","org",false,1,100,1,1000000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void recoveryEvidencePreventsCascadeDeletionUntilExternalCleanupExists() {
        var job=repo.enqueue(candidate(5));
        assertThat(new PostgresKnowledgeCleanupService(jdbc).cleanupUser("owner").safeCode()).isEqualTo("KNOWLEDGE_INDEX_CLEANUP_PENDING");
        assertThatThrownBy(()->jdbc.update("DELETE FROM platform_knowledge_index_generations WHERE id=?",job.input().generationId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repo.find(job.id())).isPresent();
    }
    @Test void databaseRejectsIncompleteConfirmedOutputEvenOutsideTheAdapter() {
        repo.enqueue(candidate(5)); var job=repo.claimNext("worker",60).orElseThrow();
        repo.planStage(job.lease(),new Manifest(job.id(),Stage.PARSING,"b".repeat(64),1));
        repo.beginBatch(job.lease(),Stage.PARSING,0,"c".repeat(64),1);
        assertThatThrownBy(()->jdbc.update("""
                UPDATE platform_knowledge_index_batches SET state='COMPLETED',output_reference=?
                WHERE job_id=? AND stage='PARSING' AND ordinal=0
                ""","knowledge-index/"+job.id()+"/result",job.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
