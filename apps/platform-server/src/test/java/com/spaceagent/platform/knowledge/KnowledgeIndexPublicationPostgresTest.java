package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.*;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.application.*;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class KnowledgeIndexPublicationPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("publication").withUsername("fixture").withPassword("fixture-password");
    static DriverManagerDataSource ds; static JdbcTemplate jdbc;
    @TempDir Path directory;
    PostgresKnowledgeIndexJobRepository jobs;
    PostgresKnowledgeIndexCatalogRepository catalog;
    PostgresKnowledgeIndexPublicationRepository publications;
    KnowledgeIndexObjectStore objects;
    KnowledgeAccessApplicationApi access;
    IdentityApplicationApi identity;
    IdentityActivityApplicationApi activity;
    EmbeddingBatchApplicationApi inference;
    @BeforeAll static void schema() {
        ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1089").load().migrate();
        var latest=Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(9);assertThat(latest.migrate().migrationsExecuted).isZero();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('org','org','org','ACTIVE',now(),now())");
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES ('owner','org','owner@test.local','Owner',now(),now())");
    }
    @BeforeEach void setup() throws Exception {
        // This PostgreSQL instance belongs solely to this test class. Prevent old fixture leases re-entering later tests.
        jdbc.update("UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL,fence=fence+1 WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')");
        jdbc.update("UPDATE platform_knowledge_index_outbox SET pending=false");
        jobs=new PostgresKnowledgeIndexJobRepository(jdbc);catalog=new PostgresKnowledgeIndexCatalogRepository(jdbc);
        publications=new PostgresKnowledgeIndexPublicationRepository(jdbc);objects=new FileSystemKnowledgeIndexObjectStore(directory.toRealPath());
        access=mock(KnowledgeAccessApplicationApi.class);identity=mock(IdentityApplicationApi.class);activity=mock(IdentityActivityApplicationApi.class);
        when(activity.isUserActive("owner")).thenReturn(true);var now=Instant.now();
        when(identity.findTenant("org")).thenReturn(Optional.of(new TenantView("org","org","org",TenantStatus.ACTIVE,now)));
        when(identity.findTenantMembership("org","owner")).thenReturn(Optional.of(new TenantMembershipView("org","owner",TenantRole.OWNER,TenantMembershipStatus.ACTIVE,now,now)));
        inference=mock(EmbeddingBatchApplicationApi.class);
        when(inference.embed(any())).thenAnswer(a->{EmbeddingBatchApplicationApi.Request r=a.getArgument(0);
            return new EmbeddingBatchApplicationApi.Result("fixture",EmbeddingBatchApplicationApi.Status.SUCCEEDED,r.inputs().stream().map(t->List.of(1.0,0.5)).toList(),1L,null,null);});
    }
    Fixture fixture() {
        var now=Instant.now();String baseId=uuid(),doc=uuid();
        var base=new KnowledgeBase(baseId,KnowledgeBase.Scope.PERSONAL,null,"owner","fixture","",KnowledgeBase.State.ACTIVE,1,now,now);
        new PostgresKnowledgeBaseRepository(jdbc).insert(base);
        when(access.authorize(any(),eq(baseId),eq(KnowledgeBase.Permission.WRITE))).thenReturn(new KnowledgeBaseApplicationApi.BaseView(base,KnowledgeBase.Permission.MANAGE));
        when(access.authorize(any(),eq(baseId),eq(KnowledgeBase.Permission.MANAGE))).thenReturn(new KnowledgeBaseApplicationApi.BaseView(base,KnowledgeBase.Permission.MANAGE));
        jdbc.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES (?,'owner','fixture','text/plain','managed-index','UPLOADED',now(),now())",doc);
        catalog.insertScopeIfAbsent(new KnowledgeDocumentScope(doc,baseId,"owner","EXPLICIT",now));
        var space=catalog.insertSpaceIfAbsent(new KnowledgeEmbeddingSpace(uuid(),baseId,"provider","fixture-model","r1","a".repeat(64),2,"b".repeat(64),"c".repeat(64),"owner",now));
        return next(new Fixture(base,doc,space,null,null),0,"source ".repeat(160));
    }
    Fixture next(Fixture old,long expectedRevision,String text) {
        return next(old,expectedRevision,text,true);
    }
    Fixture next(Fixture old,long expectedRevision,String text,boolean claim) {
        String generationId=uuid(),contentHash=hash(text.getBytes(StandardCharsets.UTF_8));
        var generation=catalog.insertGenerationIfAbsent(new KnowledgeIndexGeneration(generationId,old.base.id(),old.doc,old.space.id(),contentHash,
                KnowledgeIndexBuildCoordinator.PARSER_HASH,KnowledgeIndexBuildCoordinator.CHUNKER_HASH,hash(generationId.getBytes(StandardCharsets.UTF_8)),"owner",Instant.now()));
        String reference=objects.put(old.doc,contentHash,text.getBytes(StandardCharsets.UTF_8));
        publications.registerSource(generation.id(),expectedRevision,reference);
        var queued=jobs.enqueue(KnowledgeIndexJob.queued(uuid(),new Input(old.base.id(),generation.id(),"owner","org",uuid(),generation.fingerprint(),"dense_v1",5),Instant.now()));
        if(!claim)return new Fixture(old.base,old.doc,old.space,generation,queued);
        var claimed=jobs.claimNext("worker",90).orElseThrow();assertThat(claimed.input().generationId()).isEqualTo(generation.id());
        return new Fixture(old.base,old.doc,old.space,generation,claimed);
    }
    KnowledgeIndexActivationService activation() {return new KnowledgeIndexActivationService(publications,jobs,new PostgresKnowledgeBaseRepository(jdbc),access,activity);}
    KnowledgeIndexBuildCoordinator build(VectorIndexGateway gateway,KnowledgeIndexActivationService activation) {
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("vector",gateway);
        var embedding=new KnowledgeEmbeddingStage(jobs,catalog,access,identity,activity,inference,objects,new ObjectMapper());
        return new KnowledgeIndexBuildCoordinator(jobs,catalog,publications,objects,access,embedding,activation,beans.getBeanProvider(VectorIndexGateway.class),new ObjectMapper());
    }
    @Test void actualPgvectorPublishesRestoresAndDeletesWithoutASecondEmbeddingCall() {
        var f=fixture();var gateway=new PgVectorIndexGateway(jdbc,5);
        assertThat(build(gateway,activation()).execute(f.job.lease()).progress().state()).isEqualTo(State.COMPLETED);
        String active=publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId();
        var space=new VectorIndexGateway.Space(f.space.id(),f.space.fingerprint(),2);
        var scope=new VectorIndexGateway.Scope("user:owner",f.base.id(),List.of(active));
        assertThat(gateway.search(space,scope,List.of(1.0,.5),5)).hasSize(2);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("vectors",gateway);
        var repairs=new PostgresKnowledgeIndexRepairRepository(jdbc);
        var repair=new KnowledgeIndexRepairService(repairs,access,new PostgresKnowledgeBaseRepository(jdbc),publications,catalog,jobs,objects,beans.getBeanProvider(VectorIndexGateway.class),new ObjectMapper());
        var request=repair.request(new KnowledgeBaseApplicationApi.Actor("owner","org"),f.base.id(),f.doc,active,"pg-restore");
        jdbc.update("DELETE FROM platform_knowledge_pgvector_entries WHERE space_id=?",space.id());
        assertThat(repair.runOne()).isTrue();assertThat(repairs.find(request.id()).orElseThrow().state()).isEqualTo("COMPLETED");
        assertThat(gateway.search(space,scope,List.of(1.0,.5),5)).hasSize(2);verify(inference,times(1)).embed(any());
        var deletion=new PostgresKnowledgeIndexDeletionRepository(jdbc);deletion.request(f.base.id(),f.doc,1);
        when(inference.eraseJobCache(anyString(),anyString(),anyString())).thenReturn(true);
        var maintenance=new KnowledgeIndexMaintenanceWorker(deletion,objects,new PostgresKnowledgeObjectInventory(jdbc),beans.getBeanProvider(VectorIndexGateway.class),false,inference);
        for(int pass=0;pass<2;pass++){
            jdbc.update("UPDATE platform_knowledge_index_tombstones SET next_check_at=clock_timestamp()-interval '1 second' WHERE document_id=?",f.doc);
            assertThat(maintenance.sweepOne()).isTrue();
        }
        assertThat(publications.head(f.base.id(),f.doc)).isEmpty();assertThat(gateway.generationDeleted(space,scope,active)).isTrue();
    }
    @Test void pgvectorUnsupportedGeometryDoesNotDispatchPaidEmbeddings() {
        var f=fixture();jdbc.update("UPDATE platform_knowledge_embedding_spaces SET dimensions=16001 WHERE id=?",f.space.id());
        assertThat(build(new PgVectorIndexGateway(jdbc,5),activation()).execute(f.job.lease()).progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        verifyNoInteractions(inference);assertThat(publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId()).isNull();
    }
    @Test void completeBuildPublishesOnlyAfterAllAuthoritativeBatchesAndClosesOutboxAtomically() {
        var f=fixture();var gateway=new FakeVectors();
        assertThat(build(gateway,activation()).execute(f.job.lease()).progress().state()).isEqualTo(State.COMPLETED);
        assertThat(publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId()).isEqualTo(f.generation.id());
        assertThat(new PostgresKnowledgeIndexPublicationRepository(new JdbcTemplate(ds)).chunks(f.generation.id())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT pending FROM platform_knowledge_index_outbox WHERE job_id=?",Boolean.class,f.job.id())).isFalse();
        assertThat(jobs.renew(f.job.lease(),90)).isFalse();
        assertThat(publications.head("other-base",f.doc)).isEmpty();
    }
    @Test void failedReplacementDoesNotDestroyThePreviouslyActiveGeneration() {
        var f=fixture();var gateway=new FakeVectors();build(gateway,activation()).execute(f.job.lease());
        var replacement=next(f,1,"new content ".repeat(110));gateway.failWrites=true;
        var failed=build(gateway,activation()).execute(replacement.job.lease());
        assertThat(failed.progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThat(publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId()).isEqualTo(f.generation.id());
        assertThat(publications.head(f.base.id(),f.doc).orElseThrow().revision()).isEqualTo(2);
    }
    @Test void staleRevisionAndInvalidationFenceOffAnOtherwiseReadyGeneration() {
        var f=fixture();var gateway=new FakeVectors();
        build(gateway,mock(KnowledgeIndexActivationService.class)).execute(f.job.lease());
        assertThat(jobs.find(f.job.id()).orElseThrow().progress().stage()).isEqualTo(Stage.READY_TO_ACTIVATE);
        next(f,1,"newer content",false); // Fair scheduler queues the successor while the original lease is still held.
        assertThatThrownBy(()->publications.activate(f.job.lease())).isInstanceOf(IllegalStateException.class);
        var deleted=publications.invalidate(f.base.id(),f.doc,2);
        assertThat(deleted.state()).isEqualTo("DELETING");assertThat(deleted.activeGenerationId()).isNull();
        assertThatThrownBy(()->publications.activate(f.job.lease())).isInstanceOf(IllegalStateException.class);
    }
    @Test void restartedReadyJobRechecksActualStorageInsteadOfTrustingHistoricalReceipts() {
        var f=fixture();var gateway=new FakeVectors();build(gateway,mock(KnowledgeIndexActivationService.class)).execute(f.job.lease());
        jdbc.update("UPDATE platform_knowledge_index_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.job.id());
        jobs=new PostgresKnowledgeIndexJobRepository(new JdbcTemplate(ds));var recovered=jobs.claimNext("replacement",90).orElseThrow();
        gateway.entries.clear();
        assertThat(build(gateway,activation()).execute(recovered.lease()).progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThat(publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId()).isNull();
    }
    @Test void activationCannotSkipPersistedChunkAndVerificationProof() {
        var f=fixture();jdbc.update("UPDATE platform_knowledge_index_jobs SET stage='READY_TO_ACTIVATE' WHERE id=?",f.job.id());
        assertThatThrownBy(()->publications.activate(f.job.lease())).isInstanceOf(IllegalStateException.class);
        assertThat(publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId()).isNull();
    }
    @Test void retiredGenerationCleanupPreservesTheCurrentDocumentAndOriginalSources(){
        var first=fixture();var gateway=new FakeVectors();build(gateway,activation()).execute(first.job.lease());
        var second=next(first,1,"replacement evidence");build(gateway,activation()).execute(second.job.lease());
        jdbc.update("UPDATE platform_knowledge_index_jobs SET updated_at=clock_timestamp()-interval '25 hours' WHERE id=?",first.job.id());
        var deletion=new PostgresKnowledgeIndexDeletionRepository(jdbc);assertThat(deletion.retireSuperseded()).isEqualTo(1);
        assertThat(deletion.reserved(first.base.id(),first.doc)).isFalse();
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("vectors",gateway);when(inference.eraseJobCache(anyString(),anyString(),anyString())).thenReturn(true);
        var worker=new KnowledgeIndexMaintenanceWorker(deletion,objects,new PostgresKnowledgeObjectInventory(jdbc),beans.getBeanProvider(VectorIndexGateway.class),false,inference);
        for(int i=0;i<2;i++){jdbc.update("UPDATE platform_knowledge_index_tombstones SET next_check_at=clock_timestamp()-interval '1 second' WHERE generation_id=?",first.generation.id());assertThat(worker.sweepOne()).isTrue();}
        assertThat(publications.head(first.base.id(),first.doc).orElseThrow().activeGenerationId()).isEqualTo(second.generation.id());
        assertThat(publications.chunks(first.generation.id())).isEmpty();assertThat(publications.chunks(second.generation.id())).isNotEmpty();
        var original=publications.source(first.generation.id()).orElseThrow();assertThat(objects.read(original.sourceReference(),original.contentHash())).isNotEmpty();
        assertThat(gateway.generationDeleted(new VectorIndexGateway.Space(first.space.id(),first.space.fingerprint(),2),new VectorIndexGateway.Scope("user:owner",first.base.id(),List.of(first.generation.id())),first.generation.id())).isTrue();
        // Whole-document deletion upgrades the existing generation marker rather than skipping its document files.
        deletion.request(first.base.id(),first.doc,2);
        assertThat(jdbc.queryForObject("SELECT kind FROM platform_knowledge_index_tombstones WHERE generation_id=?",String.class,first.generation.id())).isEqualTo("DOCUMENT");
    }
    @Test @EnabledIfEnvironmentVariable(named="SPACEAGENT_MILVUS_TEST_URI",matches=".+")
    void realMilvusWriteVerifySearchAndPostgresActivationUseTheSameGeneration() {
        var f=fixture();String uri=System.getenv("SPACEAGENT_MILVUS_TEST_URI");
        var client=new MilvusIndexConfiguration().knowledgeMilvusClient(uri,System.getenv("SPACEAGENT_MILVUS_TEST_TOKEN"),"default",uri.startsWith("http://"));
        var gateway=new MilvusVectorIndexGateway(client,"publication"+uuid().replace("-","").substring(0,8));
        var space=new VectorIndexGateway.Space(f.space.id(),f.space.fingerprint(),f.space.dimensions());
        try {
            assertThat(build(gateway,activation()).execute(f.job.lease()).progress().state()).isEqualTo(State.COMPLETED);
            String active=publications.head(f.base.id(),f.doc).orElseThrow().activeGenerationId();
            assertThat(gateway.search(space,new VectorIndexGateway.Scope("user:owner",f.base.id(),List.of(active)),List.of(1.0,0.5),5))
                    .hasSize(2).allMatch(v->v.generationId().equals(f.generation.id()) && v.documentId().equals(f.doc));
            var originalChunks=publications.chunks(f.generation.id());
            var restoreBeans=new DefaultListableBeanFactory();restoreBeans.registerSingleton("vectors",gateway);
            var repairs=new PostgresKnowledgeIndexRepairRepository(jdbc);
            var repair=new KnowledgeIndexRepairService(repairs,access,new PostgresKnowledgeBaseRepository(jdbc),publications,catalog,jobs,objects,restoreBeans.getBeanProvider(VectorIndexGateway.class),new ObjectMapper());
            var repairView=repair.request(new KnowledgeBaseApplicationApi.Actor("owner","org"),f.base.id(),f.doc,active,"restore-key");
            client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder().collectionName(gateway.collectionName(space)).build());
            assertThat(repair.runOne()).isTrue();assertThat(repairs.find(repairView.id()).orElseThrow().state()).isEqualTo("COMPLETED");
            assertThat(gateway.search(space,new VectorIndexGateway.Scope("user:owner",f.base.id(),List.of(active)),List.of(1.0,.5),5)).hasSize(2);
            verify(inference,times(1)).embed(any()); // Restore used stored vectors, not a paid recomputation.
            var delete=new PostgresKnowledgeIndexDeletionRepository(jdbc);delete.request(f.base.id(),f.doc,1);
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("vectors",gateway);
            when(inference.eraseJobCache(anyString(),anyString(),anyString())).thenReturn(true); // This fixture's embedding provider never stored a model cache.
            var maintenance=new KnowledgeIndexMaintenanceWorker(delete,objects,new PostgresKnowledgeObjectInventory(jdbc),beans.getBeanProvider(VectorIndexGateway.class),false,inference);
            for(int pass=0;pass<2;pass++) {
                jdbc.update("UPDATE platform_knowledge_index_tombstones SET next_check_at=clock_timestamp()-interval '1 second' WHERE document_id=?",f.doc);
                assertThat(maintenance.sweepOne()).isTrue();
            }
            assertThat(delete.view(f.base.id(),f.doc).orElseThrow().state()).isEqualTo("DELETED");
            assertThat(publications.head(f.base.id(),f.doc)).isEmpty();
            var scope=new VectorIndexGateway.Scope("user:owner",f.base.id(),List.of(active));
            var chunk=originalChunks.getFirst();
            gateway.upsertBatch(space,List.of(new VectorIndexGateway.Entry("user:owner",f.base.id(),f.doc,active,chunk.id(),chunk.contentHash(),List.of(1.0,0.5))));
            assertThat(gateway.generationDeleted(space,scope,active)).isFalse();
            jdbc.update("UPDATE platform_knowledge_index_tombstones SET next_check_at=clock_timestamp()-interval '1 second' WHERE document_id=?",f.doc);
            assertThat(maintenance.sweepOne()).isTrue();assertThat(gateway.generationDeleted(space,scope,active)).isTrue();
        } finally {
            try {if(client.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq.builder().collectionName(gateway.collectionName(space)).build()))
                client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder().collectionName(gateway.collectionName(space)).build());}
            finally {client.close();}
        }
    }
    private record Fixture(KnowledgeBase base,String doc,KnowledgeEmbeddingSpace space,KnowledgeIndexGeneration generation,KnowledgeIndexJob job) {}
    private static String uuid(){return UUID.randomUUID().toString();}
    private static String hash(byte[] bytes){return KnowledgeIndexBuildCoordinator.hash(bytes);}
    private static class FakeVectors implements VectorIndexGateway {
        final Map<String,Entry> entries=new HashMap<>();boolean failWrites;
        public void ensureSpace(Space s) {}
        public void upsertBatch(Space s,List<Entry> values) {if(failWrites)throw new IllegalStateException("fixture write failure");values.forEach(e->entries.put(e.generationId()+e.chunkId(),e));}
        public boolean verifyBatch(Space s,List<Entry> values) {return values.stream().allMatch(v->v.equals(entries.get(v.generationId()+v.chunkId())));}
        public List<Match> search(Space s,Scope scope,List<Double> q,int limit) {return entries.values().stream().filter(e->e.scopeKey().equals(scope.key()) && e.baseId().equals(scope.baseId()) && scope.generationIds().contains(e.generationId()))
                .limit(limit).map(e->new Match(e.documentId(),e.generationId(),e.chunkId(),e.contentHash(),1)).toList();}
        public void deleteGeneration(Space s,Scope scope,String id) {entries.values().removeIf(e->e.scopeKey().equals(scope.key()) && e.baseId().equals(scope.baseId()) && e.generationId().equals(id));}
        public boolean generationDeleted(Space s,Scope scope,String id) {return entries.values().stream().noneMatch(e->e.scopeKey().equals(scope.key()) && e.baseId().equals(scope.baseId()) && e.generationId().equals(id));}
    }
}
