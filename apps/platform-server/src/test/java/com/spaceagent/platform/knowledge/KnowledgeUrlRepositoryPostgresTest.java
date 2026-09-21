package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeUrlRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class KnowledgeUrlRepositoryPostgresTest {
    private static final String HASH_A="sha256:"+"a".repeat(64);
    private static final String HASH_B="sha256:"+"b".repeat(64);
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_url").withUsername("spaceagent").withPassword("spaceagent");
    private static DriverManagerDataSource dataSource;

    @BeforeAll static void migrate(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();}

    @Test void postgresClockClaimsOnceFencesReleaseAndRestarts()throws Exception{
        Scope scope=seed(new JdbcTemplate(dataSource),"one");var repository=repository();Instant now=repository.currentTime();String jobId=UUID.randomUUID().toString();var job=job(jobId,scope,now);repository.insertJob(job);
        CyclicBarrier barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){var claims=java.util.stream.IntStream.range(0,2).mapToObj(i->CompletableFuture.supplyAsync(()->{try{barrier.await(5,TimeUnit.SECONDS);return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(x->repository().claimNext("worker-"+i,UUID.randomUUID().toString(),60));}catch(Exception e){throw new RuntimeException(e);}},pool)).toList();CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);var winners=claims.stream().map(CompletableFuture::join).flatMap(Optional::stream).toList();assertThat(winners).hasSize(1);var lease=winners.getFirst();assertThat(lease.job().revision()).isEqualTo(2);assertThat(repository.releaseLease(jobId,UUID.randomUUID().toString(),lease.fencingToken(),lease.revision(),now.plusSeconds(3600))).isFalse();assertThat(repository.releaseLease(jobId,lease.claimToken(),lease.fencingToken(),lease.revision(),now.plusSeconds(3600))).isTrue();}
        assertThat(repository().findJob(scope.tenant,scope.owner,jobId)).get().satisfies(v->{assertThat(v.revision()).isEqualTo(3);assertThat(v.nextRefreshAt()).isAfter(now);});
        assertThat(repository().findJobsByDocument(scope.tenant,scope.owner,scope.document,0,20)).extracting(KnowledgeUrlJob::id).containsExactly(jobId);
        assertThat(repository().countJobsByDocument(scope.tenant,scope.owner,scope.document)).isEqualTo(1);
        assertThat(repository().findJobsByDocument(scope.tenant,"other",scope.document,0,20)).isEmpty();
    }

    @Test void appendOnlyObservationsAndAtomicContentActivationAreScopedAndBodyFree(){
        Scope one=seed(new JdbcTemplate(dataSource),"content-one");Scope two=seed(new JdbcTemplate(dataSource),"content-two");var repository=repository();Instant now=repository.currentTime();String jobId=UUID.randomUUID().toString();repository.insertJob(job(jobId,one,now));var lease=repository.claimNext("activation-worker",UUID.randomUUID().toString(),60).orElseThrow();
        String observationId=UUID.randomUUID().toString();var observation=new KnowledgeUrlEvidence.Observation(observationId,jobId,one.tenant,HASH_A,"https://docs.example.com/page",HASH_A,200,"etag","last-modified",HASH_B,KnowledgeUrlEvidence.ObservationOutcome.FETCHED,null,now);repository.appendObservation(observation);assertThat(repository.observations(one.tenant,jobId,10)).containsExactly(observation);
        var cross=new KnowledgeUrlEvidence.Observation(UUID.randomUUID().toString(),jobId,two.tenant,HASH_A,"https://docs.example.com/page",HASH_A,304,null,null,null,KnowledgeUrlEvidence.ObservationOutcome.NOT_MODIFIED,null,now);
        assertThatThrownBy(()->repository.appendObservation(cross)).isInstanceOf(Exception.class);
        var first=content(UUID.randomUUID().toString(),jobId,one,observationId,1,HASH_A,"knowledge-url/one");repository.insertContentVersion(first);assertThat(repository.activateContentVersion(one.tenant,jobId,first.id(),UUID.randomUUID().toString(),lease.fencingToken(),lease.revision(),now.plusSeconds(1))).isEmpty();var activeOne=repository.activateContentVersion(one.tenant,jobId,first.id(),lease.claimToken(),lease.fencingToken(),lease.revision(),now.plusSeconds(1)).orElseThrow();assertThat(activeOne.state()).isEqualTo(KnowledgeUrlEvidence.ContentState.ACTIVE);
        String observationTwoId=UUID.randomUUID().toString();repository.appendObservation(new KnowledgeUrlEvidence.Observation(observationTwoId,jobId,one.tenant,HASH_A,"https://docs.example.com/page",HASH_A,200,"etag-2","last-modified-2",HASH_A,KnowledgeUrlEvidence.ObservationOutcome.FETCHED,null,now.plusSeconds(2)));var second=content(UUID.randomUUID().toString(),jobId,one,observationTwoId,2,HASH_B,"knowledge-url/two");repository.insertContentVersion(second);repository.activateContentVersion(one.tenant,jobId,second.id(),lease.claimToken(),lease.fencingToken(),lease.revision(),now.plusSeconds(3));
        assertThat(repository().contentVersions(one.tenant,jobId)).extracting(KnowledgeUrlEvidence.ContentVersion::state).containsExactly(KnowledgeUrlEvidence.ContentState.SUPERSEDED,KnowledgeUrlEvidence.ContentState.ACTIVE);
        Integer rawColumns=new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('platform_knowledge_url_jobs','platform_knowledge_url_observations','platform_knowledge_url_content_versions') AND column_name IN ('body','content','response_body','dns_address')",Integer.class);assertThat(rawColumns).isZero();
    }

    @Test void v1066UpgradesIdempotently(){String schema="knowledge_url_upgrade";Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1065")).load().migrate();var jdbc=new JdbcTemplate(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));assertThat(jdbc.queryForObject("SELECT to_regclass('platform_knowledge_url_jobs') IS NULL",Boolean.class)).isTrue();var flyway=Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();flyway.migrate();flyway.migrate();assertThat(jdbc.queryForObject("SELECT to_regclass('platform_knowledge_url_content_versions') IS NOT NULL",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("1098");}

    private static PostgresKnowledgeUrlRepository repository(){return new PostgresKnowledgeUrlRepository(new JdbcTemplate(dataSource),new DataSourceTransactionManager(dataSource));}
    private static KnowledgeUrlJob job(String id,Scope s,Instant now){return job(id,s,now,now.minusSeconds(1));}
    private static KnowledgeUrlJob job(String id,Scope s,Instant now,Instant next){var policy=new KnowledgeUrlJob.RefreshPolicy(KnowledgeUrlJob.RefreshMode.PERIODIC,3600,true,3,1_000_000);return new KnowledgeUrlJob(id,s.tenant,s.owner,s.document,"https://docs.example.com/","https://docs.example.com",policy,KnowledgeUrlJob.State.ACTIVE,1,next,now.minusSeconds(2),now.minusSeconds(2),null);}
    private static KnowledgeUrlEvidence.ContentVersion content(String id,String job,Scope s,String observation,int version,String hash,String ref){return new KnowledgeUrlEvidence.ContentVersion(id,job,s.document,s.tenant,version,hash,ref,"text/html","UTF-8",100,KnowledgeUrlEvidence.ContentState.STAGED,observation,Instant.now(),null);}
    private static Scope seed(JdbcTemplate j,String suffix){String tenant=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString(),document=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());j.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Tenant "+suffix,"knowledge-url-"+tenant,now,now);j.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",owner,tenant,owner+"@example.com",now,now);j.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES(?,?,?,'text/html','managed:pending','PENDING',?,?)",document,owner,"URL "+suffix,now,now);return new Scope(tenant,owner,document);}
    private record Scope(String tenant,String owner,String document){}
}
