package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.*;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.inference.domain.EmbeddingBatchExecutor;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.application.KnowledgeIndexBuildCoordinator;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeCleanupService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformKnowledgeIndexIntakePostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("intake").withUsername("fixture").withPassword("fixture-password");
    static final Path root=temporary();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.enabled",()->true);
        r.add("spring.flyway.locations",()->"classpath:db/platform-server,classpath:db/platform-runtime");r.add("platform.persistence",()->"postgres");
        r.add("platform.security.allow-insecure-local",()->true);r.add("platform.runtime.coordination.enabled",()->false);
        r.add("platform.inference.health-probes-enabled",()->false);r.add("platform.inference.allowed-provider-hosts",()->"example.com");
        r.add("platform.knowledge.url-refresh.enabled",()->false);r.add("platform.knowledge.indexing.intake-enabled",()->true);
        r.add("platform.knowledge.index-content-root",()->root.toString());r.add("platform.knowledge.url-content-root",()->root.resolve("url-fixture").toString());
        r.add("platform.identity.cleanup.worker-enabled",()->false);r.add("platform.identity.user-cleanup.worker-enabled",()->false);
        r.add("platform.tooling.mcp.registry.worker-enabled",()->false);
        r.add("platform.knowledge.parser.uri",()->System.getenv().getOrDefault("SPACEAGENT_TIKA_TEST_URI",""));
        r.add("platform.knowledge.parser.allow-private-http",()->true);
        r.add("platform.knowledge.limits.pending-per-tenant",()->4);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired IdentityApplicationApi identity;@Autowired KnowledgeUrlRepository urls;@Autowired KnowledgeUrlContentStore urlBytes;
    @Autowired KnowledgeIndexJobRepository indexJobs;@Autowired KnowledgeIndexBuildCoordinator build;
    @Autowired KnowledgeIndexDeletionRepository deletion;
    @Autowired com.spaceagent.platform.knowledge.application.KnowledgeIndexMaintenanceWorker maintenance;
    @Autowired com.spaceagent.platform.knowledge.application.KnowledgeIndexRepairService repair;
    @Autowired KnowledgeApplicationApi legacyApi;
    @Autowired KnowledgeObjectInventory inventory;
    @MockitoBean EmbeddingBatchExecutor executor; // Never permit paid network calls from this fixture.
    @MockitoBean VectorIndexGateway vectors;
    @MockitoSpyBean KnowledgeIndexObjectStore objects;
    boolean buildExecuted;
    int queryExecutions;
    @AfterEach void noUnexpectedEmbedding(){if(buildExecuted || queryExecutions>0)verify(executor,times((buildExecuted?1:0)+queryExecutions)).execute(any(),anyString(),anyInt(),anyList());else verifyNoInteractions(executor);}

    @Test void legacyMigrationCopiesWithoutReassigningOwnershipAndCutoverRejectsOldVectorCalls() throws Exception {
        var user=register();String doc=send(post("/api/v1/knowledge/documents"),user,Map.of("name","legacy","contentType","text/plain","storageLocation","inline:legacy source"),200).path("id").asText();
        String oldBase=jdbc.queryForObject("SELECT base_id FROM platform_knowledge_document_scopes WHERE document_id=?",String.class,doc);
        var target=base(user,"PERSONAL");String path="/api/v1/knowledge/bases/"+oldBase+"/legacy-migration";
        assertThat(send(get(path),user,null,200).get(0).path("reason").asText()).isEqualTo("LEGACY_VECTOR_PROVENANCE_UNKNOWN");
        var body=Map.of("targetBaseId",target.base(),"spaceId",target.space());
        var copied=send(post(path+"/"+doc).header("Idempotency-Key","legacy-copy"),user,body,202);
        assertThat(copied.path("documentId").asText()).isNotEqualTo(doc);assertThat(send(post(path+"/"+doc).header("Idempotency-Key","legacy-copy"),user,body,202)).isEqualTo(copied);
        assertThat(send(get(path),user,null,200).get(0).path("copiedDocumentId")).isEqualTo(copied.path("documentId"));
        assertThat(jdbc.queryForObject("SELECT owner_id FROM platform_knowledge_documents WHERE id=?",String.class,doc)).isEqualTo(user.id());
        send(get(path),register(),null,404);
        var service=org.springframework.test.util.AopTestUtils.getTargetObject(legacyApi);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"legacyMode","disabled");
        try{assertThatThrownBy(()->legacyApi.retrieve(new KnowledgeRetrievalCommand(user.id(),List.of(doc),"query",5)))
            .isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,e->assertThat(e.getCode()).isEqualTo("KNOWLEDGE_LEGACY_MIGRATION_REQUIRED"));}
        finally{org.springframework.test.util.ReflectionTestUtils.setField(service,"legacyMode","compatibility");}
    }
    @Test void intakeQuotaRejectsAtomicallyButAllowsIdempotentReplay() throws Exception {
        var f=base(register(),"PERSONAL");JsonNode first=null;
        for(int i=0;i<4;i++){var receipt=upload(f,null,0,"body","doc","quota-"+i,202);if(i==0)first=receipt;}
        upload(f,null,0,"body","doc","quota-over",429);
        assertThat(upload(f,null,0,"body","doc","quota-0",202)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE base_id=?",Integer.class,f.base())).isEqualTo(4);
        verify(objects,times(8)).put(anyString(),anyString(),any(byte[].class)); // Neither rejection nor replay writes orphan bytes.
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SPACEAGENT_TIKA_TEST_URI",matches=".+")
    void binaryAdmissionRunsIsolatedParserAndPublishesRealPageMetadata() throws Exception {
        var f=base(register(),"PERSONAL");var receipt=send(post(route(f)).param("name","PDF fixture").param("spaceId",f.space())
            .header("Idempotency-Key","binary-key").contentType("application/pdf").content(com.spaceagent.platform.knowledge.TikaDocumentParsingGatewayTest.pdf()),f.user(),null,202);
        String job=receipt.path("jobId").asText(),gen=receipt.path("generationId").asText();
        jdbc.update("UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL WHERE id<>? AND state='QUEUED'",job);
        configureBuildFakes();buildExecuted=true;
        assertThat(build.execute(indexJobs.claimNext("binary-fixture",90).orElseThrow().lease()).progress().state()).isEqualTo(KnowledgeIndexJob.State.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT metadata->>'page' FROM platform_knowledge_generation_chunks WHERE generation_id=?",String.class,gen)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT parse_metadata_reference FROM platform_knowledge_generation_sources WHERE generation_id=?",String.class,gen)).startsWith("knowledge-index/"+job+"/");
    }
    @Test void processingPolicyCasAndPreviewRemainLocalAndSnapshotsAreImmutable() throws Exception {
        var f=base(register(),"PERSONAL");String path="/api/v1/knowledge/bases/"+f.base();
        assertThat(send(get(path+"/processing-policy"),f.user(),null,200).path("policy").path("strategy").asText()).isEqualTo("RECURSIVE_TOKEN");
        var policy=Map.of("strategy","MARKDOWN_SECTION","size",128,"overlap",16);
        send(put(path+"/processing-policy"),f.user(),Map.of("expectedRevision",0,"policy",policy),200);
        send(put(path+"/processing-policy"),f.user(),Map.of("expectedRevision",0,"policy",policy),409);
        var preview=send(post(path+"/chunk-preview"),f.user(),Map.of("content","# 标题\n企业知识库内容\n## Recovery\nDurable evidence","policy",policy),200);
        assertThat(preview.size()).isEqualTo(2);assertThat(preview.get(1).path("metadata").path("headingPath").size()).isEqualTo(2);
        var saved=upload(f,null,0,"# 标题\nindex policy","policy","policy-key",202);
        send(put(path+"/processing-policy"),f.user(),Map.of("expectedRevision",1,"policy",Map.of("strategy","RECURSIVE_TOKEN","size",512,"overlap",64)),200);
        assertThat(jdbc.queryForObject("SELECT chunking_policy->>'strategy' FROM platform_knowledge_generation_sources WHERE generation_id=?",String.class,saved.path("generationId").asText())).isEqualTo("MARKDOWN_SECTION");
        send(post(path+"/chunk-preview"),register(),Map.of("content","secret","policy",policy),404);
    }
    @Test void publishedCollectionRetrievalUsesCurrentActorAndRemovesDeletedAndSupersededEvidence() throws Exception {
        var f=base(register(),"PERSONAL");var receipt=upload(f,null,0,"retrievable knowledge","retrieval fixture","retrieve-build",202);
        String job=receipt.path("jobId").asText(),gen=receipt.path("generationId").asText(),doc=receipt.path("documentId").asText();
        jdbc.update("UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL WHERE id<>? AND state='QUEUED'",job);
        configureBuildFakes();buildExecuted=true;assertThat(build.execute(indexJobs.claimNext("retrieval-fixture",90).orElseThrow().lease()).progress().state()).isEqualTo(KnowledgeIndexJob.State.COMPLETED);
        var c=jdbc.queryForMap("SELECT chunk_id,content_hash FROM platform_knowledge_generation_chunks WHERE generation_id=?",gen);
        var hit=new VectorIndexGateway.Match(doc,gen,(String)c.get("chunk_id"),(String)c.get("content_hash"),.9);
        doReturn(List.of(hit)).when(vectors).search(any(),any(),anyList(),anyInt());doReturn(List.of(hit)).when(vectors).lexicalSearch(any(),any(),anyString(),anyInt());
        var body=Map.of("baseIds",List.of(f.base()),"query","what knowledge","topK",5,"maxContextTokens",512);
        String path="/api/v1/knowledge/collections/retrieve";
        send(post(path),f.user(),body,400);send(post(path).header("Idempotency-Key","foreign"),register(),body,404);
        queryExecutions=1;var found=send(post(path).header("Idempotency-Key","query-key"),f.user(),body,200);
        assertThat(found.path("hits").size()).isEqualTo(1);assertThat(found.path("hits").get(0).path("content").asText()).isEqualTo("retrievable knowledge");
        assertThat(found.path("hits").get(0).path("citation").path("generationId").asText()).isEqualTo(gen);
        assertThat(send(post(path).header("Idempotency-Key","query-key"),f.user(),body,200).path("hits")).isEqualTo(found.path("hits"));
        var repairRequest=send(post(route(f)+"/"+doc+"/repair").header("Idempotency-Key","repair-key"),f.user(),Map.of("generationId",gen),202);
        assertThat(repair.runOne()).isTrue();assertThat(send(get("/api/v1/knowledge/index-repairs/"+repairRequest.path("id").asText()),f.user(),null,200).path("state").asText()).isEqualTo("COMPLETED");
        // Desired revision can advance while the old complete generation remains available.
        upload(f,doc,1,"replacement not indexed","pending","pending-build",202);
        assertThat(send(post(path).header("Idempotency-Key","query-key"),f.user(),body,200).path("hits").size()).isEqualTo(1);
        send(delete(route(f)+"/"+doc).param("expectedRevision","2"),f.user(),null,202);
        assertThat(send(post(path).header("Idempotency-Key","query-key"),f.user(),body,200).path("hits").isEmpty()).isTrue();
    }

    @Test void fileIntakeIsAtomicIdempotentAndRevisionAwareIncludingContentReversion() throws Exception {
        var user=register();var f=base(user,"PERSONAL");String key=UUID.randomUUID().toString();
        var first=upload(f,null,0,"A","first",key,202);
        assertThat(upload(f,null,0,"A","first",key,202)).isEqualTo(first);
        upload(f,null,0,"changed","first",key,409);
        String doc=first.path("documentId").asText();
        var second=upload(f,doc,1,"B","second","second-key",202);
        var third=upload(f,doc,2,"A","third","third-key",202);
        assertThat(first.path("generationId").asText()).isNotEqualTo(third.path("generationId").asText());
        assertThat(second.path("documentRevision").asInt()).isEqualTo(2);
        assertThat(third.path("documentRevision").asInt()).isEqualTo(3);
        upload(f,doc,1,"outdated","stale","stale-key",409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE document_id=?",Integer.class,doc)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_jobs j JOIN platform_knowledge_index_generations g ON g.id=j.generation_id WHERE g.document_id=?",Integer.class,doc)).isEqualTo(3);
        var view=send(get(route(f)+"/"+doc),user,null,200);
        assertThat(view.path("revision").asInt()).isEqualTo(3);assertThat(view.path("activeGenerationId").isNull()).isTrue();
        assertThat(view.path("name").asText()).isEqualTo("third");
        send(get("/api/v1/knowledge/documents/"+doc),user,null,404); // Legacy private API cannot mutate new scoped sources.
        String provider=jdbc.queryForObject("SELECT provider_id FROM platform_knowledge_embedding_spaces WHERE id=?",String.class,f.space());
        String nextSpace=send(post("/api/v1/knowledge/bases/"+f.base()+"/embedding-spaces"),user,Map.of("providerId",provider,"modelId","embedding-fixture",
                "modelRevision","r2","dimensions",2,"preprocessingHash","a".repeat(64)),201).path("space").path("id").asText();
        var changedModel=upload(new Fixture(user,f.base(),nextSpace),doc,3,"A","new model","model-key",202);
        assertThat(changedModel.path("documentRevision").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT space_id FROM platform_knowledge_index_generations WHERE id=?",String.class,changedModel.path("generationId").asText())).isEqualTo(nextSpace);
    }
    @Test void permissionsAndOwnershipSurviveKnowledgeCleanupOfTheOrganizationCreator() throws Exception {
        var owner=register();var member=join(register(),owner.org(),TenantRole.ADMIN);var outsider=register();var f=base(member,"ORGANIZATION");
        String doc=upload(f,null,0,"organization-owned content","org-doc","org-key",202).path("documentId").asText();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM platform_knowledge_documents WHERE id=?",String.class,doc)).isNull();
        assertThat(new PostgresKnowledgeCleanupService(jdbc).cleanupUser(member.id()).blocked()).isFalse();
        assertThat(send(get(route(f)+"/"+doc),owner,null,200).path("scope").asText()).isEqualTo("ORGANIZATION");
        send(get(route(f)+"/"+doc),outsider,null,404);
        var ownBase=base(owner,"PERSONAL");
        upload(ownBase,doc,1,"foreign overwrite","x","cross-base-key",404);
        var reader=join(register(),owner.org());
        send(put("/api/v1/knowledge/bases/"+f.base()+"/grants"),owner,Map.of("subjectType","USER","subjectId",reader.id(),"permission","READ","expectedRevision",1),200);
        var readFixture=new Fixture(reader,f.base(),f.space());
        upload(readFixture,null,0,"denied","x","reader-key",403);
    }
    @Test void URLSnapshotIsCopiedVerifiedAndParsedWithoutRefetchOrGlobalEmbedding() throws Exception {
        var user=register();var f=base(user,"PERSONAL");
        String legacy=send(post("/api/v1/knowledge/documents"),user,Map.of("name","url-source","contentType","text/html","storageLocation","inline:reference"),200).path("id").asText();
        var now=Instant.now();String urlJob=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        urls.insertJob(new KnowledgeUrlJob(urlJob,user.org(),user.id(),legacy,"https://example.com/","https://example.com",
                new KnowledgeUrlJob.RefreshPolicy(KnowledgeUrlJob.RefreshMode.MANUAL,0,true,1,100000),KnowledgeUrlJob.State.PAUSED,1,null,now,now,null));
        byte[] raw="<h1>Readable evidence</h1><script>secretScript()</script>".getBytes(StandardCharsets.UTF_8);
        String rawHash="sha256:"+KnowledgeIndexBuildCoordinator.hash(raw);String ref=urlBytes.put(urlJob,rawHash,raw);
        String observation=UUID.randomUUID().toString();
        urls.appendObservation(new KnowledgeUrlEvidence.Observation(observation,urlJob,user.org(),rawHash,"https://example.com/",rawHash,200,null,null,rawHash,KnowledgeUrlEvidence.ObservationOutcome.FETCHED,null,now));
        urls.insertContentVersion(new KnowledgeUrlEvidence.ContentVersion(version,urlJob,legacy,user.org(),1,rawHash,ref,"text/html","UTF-8",raw.length,
                KnowledgeUrlEvidence.ContentState.STAGED,observation,now,null));
        Map<String,Object> body=Map.of("name","copied URL","spaceId",f.space(),"urlJobId",urlJob,"versionId",version);
        var response=send(post(route(f)+"/from-url-snapshot").header("Idempotency-Key","url-key"),user,body,202);
        String copied=response.path("documentId").asText();assertThat(copied).isNotEqualTo(legacy);
        var saved=jdbc.queryForMap("SELECT source_kind,original_hash FROM platform_knowledge_index_intake_requests WHERE document_id=?",copied);
        assertThat(saved).containsEntry("source_kind","URL_SNAPSHOT").containsEntry("original_hash",rawHash.substring(7));
        assertThat(jdbc.queryForObject("SELECT content_hash FROM platform_knowledge_index_generations WHERE id=?",String.class,response.path("generationId").asText()))
                .isEqualTo(KnowledgeIndexBuildCoordinator.hash("Readable evidence".getBytes(StandardCharsets.UTF_8)));
        var other=base(register(),"PERSONAL");
        send(post(route(other)+"/from-url-snapshot").header("Idempotency-Key","forbidden"),other.user(),
                Map.of("name","foreign","spaceId",other.space(),"urlJobId",urlJob,"versionId",version),404);
        String sourceBase=jdbc.queryForObject("SELECT base_id FROM platform_knowledge_document_scopes WHERE document_id=?",String.class,legacy);
        var revoked=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(a->{if(revoked.compareAndSet(false,true)) jdbc.update("UPDATE platform_knowledge_bases SET state='ARCHIVED',revision=revision+1 WHERE id=?",sourceBase);
            return a.callRealMethod();}).when(objects).put(anyString(),anyString(),any(byte[].class));
        send(post(route(f)+"/from-url-snapshot").header("Idempotency-Key","mid-copy-revoke"),user,body,404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE base_id=?",Integer.class,f.base())).isEqualTo(1);
        send(post(route(f)+"/from-url-snapshot").header("Idempotency-Key","archived-source"),user,body,404);
    }
    @Test void headerSizeAndEncodingErrorsDoNotCreateAuthoritativeJobs() throws Exception {
        var f=base(register(),"PERSONAL");
        var request=post(route(f)).param("name","file").param("spaceId",f.space()).contentType("text/plain").content("content");
        send(request,f.user(),null,400);
        send(post(route(f)).param("name","file").header("Idempotency-Key","no-space").contentType("text/plain").content("content"),f.user(),null,400);
        send(post(route(f)).param("name","file").param("spaceId",f.space()).header("Idempotency-Key","pdf")
                .contentType("application/octet-stream").content("not supported"),f.user(),null,415);
        send(post(route(f)).param("name","file").param("spaceId",f.space()).header("Idempotency-Key","bad-encoding")
                .contentType("text/plain").content(new byte[]{(byte)0xc3,0x28}),f.user(),null,422);
        send(post(route(f)).param("name","file").param("spaceId",f.space()).header("Idempotency-Key","large")
                .contentType("text/plain").content(new byte[8_000_001]),f.user(),null,413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE base_id=?",Integer.class,f.base())).isZero();
        mvc.perform(post(route(f)).param("name","file").param("spaceId",f.space()).contentType("text/plain").content("x")).andExpect(status().isUnauthorized());
    }
    @Test void concurrentRevisionsHaveOneWinnerAndNoPartialRequestOrJob() throws Exception {
        var f=base(register(),"PERSONAL");String doc=upload(f,null,0,"original","original","create",202).path("documentId").asText();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return competingUpdate(f,doc,"a");});
            var b=pool.submit(()->{start.await();return competingUpdate(f,doc,"b");});start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE document_id=?",Integer.class,doc)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revision FROM platform_knowledge_document_heads WHERE document_id=?",Long.class,doc)).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void publicOrganizationIntakeFeedsTheRealPostgresBuildAndAccountingPath() throws Exception {
        var f=base(register(),"ORGANIZATION");var receipt=upload(f,null,0,"organization knowledge","build fixture","build",202);
        String job=receipt.path("jobId").asText();
        // Only this disposable database is touched; older test methods intentionally left their queued fixtures unexecuted.
        jdbc.update("UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL WHERE id<>? AND state='QUEUED'",job);
        configureBuildFakes();
        buildExecuted=true;var lease=indexJobs.claimNext("fixture-worker",90).orElseThrow();assertThat(lease.id()).isEqualTo(job);
        assertThat(build.execute(lease.lease()).progress().state()).isEqualTo(KnowledgeIndexJob.State.COMPLETED);
        var current=send(get(route(f)+"/"+receipt.path("documentId").asText()),f.user(),null,200);
        assertThat(current.path("activeGenerationId").asText()).isEqualTo(receipt.path("generationId").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM platform_inference_budget_reservations WHERE embedding_call_id=(SELECT id FROM platform_embedding_calls WHERE actor_id=?)",String.class,f.user().id())).isEqualTo("SETTLED");
    }
    @Test void deletionFencesJobsSweepsTwiceAndRetainsTombstoneAfterMetadataPurge() throws Exception {
        var f=base(register(),"PERSONAL");var created=upload(f,null,0,"erase me","erase","delete-key",202);
        String doc=created.path("documentId").asText();String path=route(f)+"/"+doc;
        send(delete(path).param("expectedRevision","9"),f.user(),null,409);
        var first=send(delete(path).param("expectedRevision","1"),f.user(),null,202);
        assertThat(first.path("state").asText()).isEqualTo("DELETING");
        send(delete(path).param("expectedRevision","1"),f.user(),null,202);
        assertThat(indexJobs.find(created.path("jobId").asText()).orElseThrow().progress().state()).isEqualTo(KnowledgeIndexJob.State.CANCELLED);
        assertThat(maintenance.sweepOne()).isFalse(); // Grace period has not elapsed.
        due(doc);assertThat(maintenance.sweepOne()).isTrue();assertThat(deletion.view(f.base(),doc).orElseThrow().state()).isEqualTo("DELETING");
        due(doc);assertThat(maintenance.sweepOne()).isTrue();assertThat(deletion.view(f.base(),doc).orElseThrow().state()).isEqualTo("DELETED");
        assertThat(deletion.scopeCleanup(f.user().id(),null)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_documents WHERE id=?",Integer.class,doc)).isZero();
        assertThat(deletion.reserved(f.base(),doc)).isTrue();
        upload(f,null,0,"erase me","erase","delete-key",410);
        byte[] late="late write".getBytes(StandardCharsets.UTF_8);String hash=KnowledgeIndexBuildCoordinator.hash(late);
        String ref=objects.put(doc,hash,late);due(doc);assertThat(maintenance.sweepOne()).isTrue();
        assertThatThrownBy(()->objects.read(ref,hash)).isInstanceOf(IllegalStateException.class);
    }
    @Test void orphanGcDeletesOnlyOldUnreferencedContentAndProtectsLiveSource() throws Exception {
        var f=base(register(),"PERSONAL");var doc=upload(f,null,0,"keep me","kept","gc-kept",202).path("documentId").asText();
        String hash=KnowledgeIndexBuildCoordinator.hash("keep me".getBytes(StandardCharsets.UTF_8));String kept="knowledge-index/"+doc+"/"+hash;
        String owner=UUID.randomUUID().toString();byte[] orphan="orphan".getBytes(StandardCharsets.UTF_8);String orphanHash=KnowledgeIndexBuildCoordinator.hash(orphan);
        String ref=objects.put(owner,orphanHash,orphan);
        assertThat(inventory.deleteIfOrphan(ref,objects)).isFalse();
        jdbc.update("UPDATE platform_knowledge_object_inventory SET last_seen_at=clock_timestamp()-interval '2 days' WHERE reference IN (?,?)",ref,kept);
        assertThat(inventory.deleteIfOrphan(kept,objects)).isFalse();assertThat(objects.read(kept,hash)).isNotEmpty();
        assertThat(inventory.deleteIfOrphan(ref,objects)).isTrue();assertThatThrownBy(()->objects.read(ref,orphanHash)).isInstanceOf(IllegalStateException.class);
    }
    void configureBuildFakes(){
        var entries=new HashMap<String,VectorIndexGateway.Entry>();
        doAnswer(a->{List<VectorIndexGateway.Entry> rows=a.getArgument(1);rows.forEach(e->entries.put(e.chunkId(),e));return null;})
                .when(vectors).upsertBatch(any(),anyList());
        when(vectors.verifyBatch(any(),anyList())).thenAnswer(a->{List<VectorIndexGateway.Entry> rows=a.getArgument(1);return rows.stream().allMatch(e->e.equals(entries.get(e.chunkId())));});
        when(vectors.search(any(),any(),anyList(),anyInt())).thenAnswer(a->{VectorIndexGateway.Scope scope=a.getArgument(1);
            return entries.values().stream().filter(e->e.baseId().equals(scope.baseId()) && e.scopeKey().equals(scope.key()) && scope.generationIds().contains(e.generationId()))
                    .limit(1).map(e->new VectorIndexGateway.Match(e.documentId(),e.generationId(),e.chunkId(),e.contentHash(),1)).toList();});
        when(executor.execute(any(),anyString(),anyInt(),anyList())).thenAnswer(a->{List<String> input=a.getArgument(3);
            return new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,input.stream().map(t->List.of(1.0,0.5)).toList(),3L,null);});
    }
    @Test void confirmedEmbeddingRecoveryUsesCachedOutcomeWithoutPayingTwice() throws Exception {
        var f=base(register(),"PERSONAL");var result=upload(f,null,0,"recoverable content","recovery","recovery-key",202);
        String job=result.path("jobId").asText();
        jdbc.update("UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL WHERE id<>? AND state='QUEUED'",job);
        configureBuildFakes();buildExecuted=true;
        doAnswer(a->{if(indexJobs.find(job).orElseThrow().progress().stage()==KnowledgeIndexJob.Stage.EMBEDDING)throw new IllegalStateException("fixture crash after model success");return a.callRealMethod();})
            .when(objects).put(eq(job),anyString(),any(byte[].class));
        var claim=indexJobs.claimNext("first-worker",90).orElseThrow();
        var unknown=build.execute(claim.lease());assertThat(unknown.progress().state()).isEqualTo(KnowledgeIndexJob.State.RECONCILIATION_REQUIRED);
        doCallRealMethod().when(objects).put(eq(job),anyString(),any(byte[].class));
        var repaired=send(post("/api/v1/knowledge/index-jobs/"+job+"/reconcile"),f.user(),Map.of("expectedRevision",unknown.progress().revision()),200);
        assertThat(repaired.path("state").asText()).isEqualTo("QUEUED");
        var next=indexJobs.claimNext("recovery-worker",90).orElseThrow();
        assertThat(build.execute(next.lease()).progress().state()).isEqualTo(KnowledgeIndexJob.State.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_embedding_calls WHERE actor_id=?",Integer.class,f.user().id())).isEqualTo(1);
    }
    void due(String doc){jdbc.update("UPDATE platform_knowledge_index_tombstones SET next_check_at=clock_timestamp()-interval '1 second' WHERE document_id=?",doc);}
    int competingUpdate(Fixture f,String doc,String key) throws Exception {
        return mvc.perform(post(route(f)).header("Authorization","Bearer "+f.user().token()).header("Idempotency-Key",key)
                .param("name",key).param("spaceId",f.space()).param("documentId",doc).param("expectedRevision","1")
                .contentType("text/plain").content(key)).andReturn().getResponse().getStatus();
    }
    Fixture base(User user,String scope) throws Exception {
        String base=send(post("/api/v1/knowledge/bases"),user,Map.of("scope",scope,"name","fixture"),201).path("base").path("id").asText();
        String provider=send(post("/api/v1/model-providers"),user,Map.of("name","embedding-"+UUID.randomUUID(),"type","openai","baseUrl","https://example.com/v1",
                "apiKey","fixture-secret","models",List.of(Map.of("modelId","embedding-fixture","displayName","Fixture"))),201).path("id").asText();
        String space=send(post("/api/v1/knowledge/bases/"+base+"/embedding-spaces"),user,Map.of("providerId",provider,"modelId","embedding-fixture",
                "modelRevision","r1","dimensions",2,"preprocessingHash","a".repeat(64)),201).path("space").path("id").asText();
        return new Fixture(user,base,space);
    }
    JsonNode upload(Fixture f,String doc,long revision,String content,String name,String key,int status) throws Exception {
        var r=post(route(f)).header("Idempotency-Key",key).param("name",name).param("spaceId",f.space())
                .param("expectedRevision",Long.toString(revision)).contentType("text/plain").content(content);
        if(doc!=null)r.param("documentId",doc);return send(r,f.user(),null,status);
    }
    User register() throws Exception {
        var r=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "username","intake-"+UUID.randomUUID()+"@example.test","password","password123","displayName","Intake"))))
                .andExpect(status().isOk()).andReturn();var a=json.readTree(r.getResponse().getContentAsByteArray()).path("data");
        return new User(a.path("userId").asText(),a.path("tenantId").asText(),a.path("token").asText());
    }
    User join(User user,String org) throws Exception {
        return join(user,org,TenantRole.MEMBER);
    }
    User join(User user,String org,TenantRole role) throws Exception {
        identity.addTenantMembership(new AddTenantMembershipCommand(org,user.id(),role));
        return new User(user.id(),org,send(post("/api/v1/organizations/"+org+"/switch"),user,null,200).path("token").asText());
    }
    JsonNode send(MockHttpServletRequestBuilder r,User user,Object body,int expected) throws Exception {
        r.header("Authorization","Bearer "+user.token());if(body!=null)r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(r).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    static String route(Fixture f){return "/api/v1/knowledge/bases/"+f.base()+"/indexed-documents";}
    record User(String id,String org,String token){} record Fixture(User user,String base,String space){}
    static Path temporary(){try{return Files.createTempDirectory("knowledge-intake-test-").toRealPath();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @AfterAll static void cleanupFiles() throws Exception {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
}
