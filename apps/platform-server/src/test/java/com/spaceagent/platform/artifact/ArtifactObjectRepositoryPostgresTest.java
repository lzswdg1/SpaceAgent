package com.spaceagent.platform.artifact;

import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.platform.artifact.infrastructure.persistence.PostgresArtifactObjectRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ArtifactObjectRepositoryPostgresTest {
    @BeforeEach void isolateDeletionQueue(){
        // The prior retry test deliberately leaves a PENDING job. This dedicated fixture queue is not shared business data.
        new JdbcTemplate(dataSource).update("DELETE FROM platform_artifact_object_deletions");
    }
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("artifact_objects").withUsername("spaceagent").withPassword("spaceagent");private static DriverManagerDataSource dataSource;
    @BeforeAll static void migrate(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();}
    @Test void verifiedPublicationsHaveIndependentIdentitiesAndSurviveRestart(){Scope s=seed("publish");var r=repo();Instant now=r.currentTime();var first=verified(s,"request-1","artifact-staging:first",now);r.insertStaging(first);var one=r.publish(s.tenant,first.id(),first.revision(),object(s,"artifact-object:first",now),now.plusSeconds(2));assertThat(one.reusedExisting()).isFalse();var second=verified(s,"request-2","artifact-staging:second",now);r.insertStaging(second);var two=r.publish(s.tenant,second.id(),second.revision(),object(s,"artifact-object:second",now),now.plusSeconds(3));assertThat(two.reusedExisting()).isFalse();assertThat(two.object().id()).isNotEqualTo(one.object().id());assertThat(two.redundantStagingReference()).isNull();assertThat(repo().findObject(s.tenant,one.object().id())).contains(one.object());}
    @Test void referencesHoldsAndDeletionUseScopeCasLeaseAndFence(){Scope s=seed("delete");var r=repo();Instant now=r.currentTime();var stage=verified(s,"request","artifact-staging:delete",now);r.insertStaging(stage);var object=r.publish(s.tenant,stage.id(),stage.revision(),object(s,"artifact-object:delete",now),now.plusSeconds(1)).object();var reference=new ArtifactObjectReference(UUID.randomUUID().toString(),s.tenant,object.id(),ArtifactObjectReference.OwnerType.PROJECT,UUID.randomUUID().toString(),"patch",ArtifactObjectReference.State.ACTIVE,1,now,null);r.insertReference(reference);assertThat(r.updateReference(reference.release(now.plusSeconds(2)),1)).isPresent();var hold=new ArtifactObjectLegalHold(UUID.randomUUID().toString(),s.tenant,object.id(),HASH,s.owner,ArtifactObjectLegalHold.State.ACTIVE,1,now,null,null);r.insertHold(hold);assertThat(r.updateHold(hold.release(s.owner,now.plusSeconds(3)),1)).isPresent();var deletion=new ArtifactObjectDeletionJob(UUID.randomUUID().toString(),s.tenant,object.id(),ArtifactObjectDeletionJob.State.PENDING,null,null,null,0,null,1,now,now,null);r.scheduleDeletion(object.requestDeletion(0,false,now.plusSeconds(4)),object.revision(),deletion);var pending=r.findObject(s.tenant,object.id()).orElseThrow();var claim=r.claimDeletion("worker",UUID.randomUUID().toString(),60).orElseThrow();assertThat(r.finishDeletion(s.tenant,claim.id(),claim.revision(),"wrong",claim.fencingToken(),pending.deleted(now.plusSeconds(5)),pending.revision(),ArtifactObjectDeletionJob.State.COMPLETED,null,now)).isEmpty();assertThat(r.finishDeletion(s.tenant,claim.id(),claim.revision(),claim.claimToken(),claim.fencingToken(),pending.deleted(now.plusSeconds(5)),pending.revision(),ArtifactObjectDeletionJob.State.COMPLETED,null,now)).isPresent();}
    @Test void blockedDeletionCanOnlyReturnToPendingThroughExplicitAtomicRetry(){Scope s=seed("retry");var r=repo();Instant now=r.currentTime();var stage=verified(s,"request-retry","artifact-staging:retry",now);r.insertStaging(stage);var object=r.publish(s.tenant,stage.id(),stage.revision(),object(s,"artifact-object:retry",now),now.plusSeconds(1)).object();var deletion=new ArtifactObjectDeletionJob(UUID.randomUUID().toString(),s.tenant,object.id(),ArtifactObjectDeletionJob.State.PENDING,null,null,null,0,null,1,now,now,null);r.scheduleDeletion(object.requestDeletion(0,false,now.plusSeconds(2)),object.revision(),deletion);var pending=r.findObject(s.tenant,object.id()).orElseThrow();var claim=r.claimDeletion("worker",UUID.randomUUID().toString(),60).orElseThrow();assertThat(r.finishDeletion(s.tenant,claim.id(),claim.revision(),claim.claimToken(),claim.fencingToken(),pending.blockDeletion(now.plusSeconds(3)),pending.revision(),ArtifactObjectDeletionJob.State.BLOCKED,"ARTIFACT_BYTES_DELETE_BLOCKED",now.plusSeconds(3))).isPresent();assertThat(r.claimDeletion("automatic",UUID.randomUUID().toString(),60)).isEmpty();assertThat(r.retryBlockedDeletion(s.tenant,object.id(),now.plusSeconds(4))).get().extracting(ArtifactObjectDeletionJob::state).isEqualTo(ArtifactObjectDeletionJob.State.PENDING);assertThat(r.findObject(s.tenant,object.id())).get().extracting(ManagedArtifactObject::state).isEqualTo(ManagedArtifactObject.State.DELETE_PENDING);}
    @Test void v1069UpgradesIdempotently(){String schema="artifact_object_upgrade";Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1068")).load().migrate();var jdbc=new JdbcTemplate(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));assertThat(jdbc.queryForObject("SELECT to_regclass('platform_artifact_objects') IS NULL",Boolean.class)).isTrue();var flyway=Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();flyway.migrate();flyway.migrate();assertThat(jdbc.queryForObject("SELECT to_regclass('platform_artifact_object_deletions') IS NOT NULL",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("1098");}
    @Test void heldObjectMutexFencesConcurrentDeletionAdmission() throws Exception {
        Scope scope=seed("mutex");var repository=repo();Instant now=repository.currentTime();
        var staging=verified(scope,"mutex","artifact-staging:mutex",now);repository.insertStaging(staging);
        var object=repository.publish(scope.tenant,staging.id(),staging.revision(),object(scope,"artifact-object:mutex",now),now).object();
        var job=new ArtifactObjectDeletionJob(UUID.randomUUID().toString(),scope.tenant,object.id(),ArtifactObjectDeletionJob.State.PENDING,null,null,null,0,null,1,now,now,null);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<java.util.Optional<ArtifactObjectDeletionJob>>>();
        try {
            new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status->{
                var jdbc=new JdbcTemplate(dataSource);
                jdbc.query("SELECT id FROM platform_artifact_objects WHERE id=CAST(? AS UUID) FOR UPDATE",rs->{},object.id());
                future.set(executor.submit(()->{entered.countDown();return repository.scheduleDeletion(object.requestDeletion(0,false,now),object.revision(),job);}));
                try{assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}
                repository.insertHold(new ArtifactObjectLegalHold(UUID.randomUUID().toString(),scope.tenant,object.id(),HASH,scope.owner,ArtifactObjectLegalHold.State.ACTIVE,1,now,null,null));
            });
            assertThat(future.get().get(5,java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
            assertThat(repository.findObject(scope.tenant,object.id()).orElseThrow().state()).isEqualTo(ManagedArtifactObject.State.READY);
        } finally{executor.shutdownNow();}
    }
    private static final String HASH="sha256:"+"a".repeat(64);private static PostgresArtifactObjectRepository repo(){return new PostgresArtifactObjectRepository(new JdbcTemplate(dataSource),new DataSourceTransactionManager(dataSource));}
    private static ArtifactObjectStagingSession verified(Scope s,String request,String ref,Instant now){return new ArtifactObjectStagingSession(UUID.randomUUID().toString(),s.tenant,s.owner,request,HASH,5,"text/plain","tenant-key:v1",null,ArtifactObjectStagingSession.State.OPEN,null,1,now.plusSeconds(300),now,now).verify(HASH,5,ref,now.plusSeconds(1));}
    private static ManagedArtifactObject object(Scope s,String ref,Instant now){return new ManagedArtifactObject(UUID.randomUUID().toString(),s.tenant,s.owner,HASH,5,"text/plain",ref,"tenant-key:v1",new ManagedArtifactObject.Retention(now,true),ManagedArtifactObject.State.READY,1,now,now,null);}
    private static Scope seed(String suffix){JdbcTemplate j=new JdbcTemplate(dataSource);String tenant=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());j.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Tenant "+suffix,"artifact-object-"+tenant,now,now);j.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",owner,tenant,owner+"@example.com",now,now);return new Scope(tenant,owner);}private record Scope(String tenant,String owner){}
}
