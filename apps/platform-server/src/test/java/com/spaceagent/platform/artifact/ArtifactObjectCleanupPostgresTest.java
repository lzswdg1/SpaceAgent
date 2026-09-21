package com.spaceagent.platform.artifact;

import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.platform.artifact.infrastructure.persistence.*;
import com.spaceagent.shared.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ArtifactObjectCleanupPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("artifact_cleanup").withUsername("spaceagent").withPassword("spaceagent");private static DriverManagerDataSource ds;
    @BeforeAll static void migrate(){ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();}
    @Test void organizationCleanupDeletesBytesBeforeMetadataAndFailureBlocks(){Scope first=seed("pass");var repo=repo();var object=publish(repo,first);AtomicBoolean bytes=new AtomicBoolean();ArtifactObjectStorageGateway storage=storage(bytes,false);new PostgresArtifactCleanupService(new JdbcTemplate(ds),repo,storage).cleanupOrganization(first.tenant);assertThat(bytes).isTrue();assertThat(repo.findObject(first.tenant,object.id())).isEmpty();Scope second=seed("blocked");var blocked=publish(repo,second);assertThatThrownBy(()->new PostgresArtifactCleanupService(new JdbcTemplate(ds),repo,storage(new AtomicBoolean(),true)).cleanupOrganization(second.tenant)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("ARTIFACT_BYTES_DELETE_BLOCKED"));assertThat(repo.findObject(second.tenant,blocked.id())).isPresent();}
    private static ArtifactObjectStorageGateway storage(AtomicBoolean called,boolean fail){return new ArtifactObjectStorageGateway(){public StagingHandle createStaging(CreateStagingCommand c){throw new UnsupportedOperationException();}public WriteResult write(WriteCommand c){throw new UnsupportedOperationException();}public Verification verify(VerifyCommand c){throw new UnsupportedOperationException();}public PublishResult publish(PublishCommand c){throw new UnsupportedOperationException();}public ReadResult read(ReadQuery q){throw new UnsupportedOperationException();}public void deleteStaging(DeleteStagingCommand c){called.set(true);}public void deleteObject(DeleteObjectCommand c){if(fail)throw new IllegalStateException();called.set(true);}};}
    private static ManagedArtifactObject publish(PostgresArtifactObjectRepository repo,Scope s){Instant now=repo.currentTime();String hash="sha256:"+"a".repeat(64);var stage=new ArtifactObjectStagingSession(UUID.randomUUID().toString(),s.tenant,s.owner,"request",hash,0,"text/plain","tenant-key:v1",null,ArtifactObjectStagingSession.State.OPEN,null,1,now.plusSeconds(60),now,now).verify(hash,0,"artifact-staging:one",now);repo.insertStaging(stage);var object=new ManagedArtifactObject(UUID.randomUUID().toString(),s.tenant,s.owner,hash,0,"text/plain","artifact-object:one","tenant-key:v1",new ManagedArtifactObject.Retention(now,true),ManagedArtifactObject.State.READY,1,now,now,null);return repo.publish(s.tenant,stage.id(),stage.revision(),object,now).object();}
    private static PostgresArtifactObjectRepository repo(){return new PostgresArtifactObjectRepository(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));}private static Scope seed(String suffix){JdbcTemplate j=new JdbcTemplate(ds);String tenant=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());j.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Tenant "+suffix,"artifact-cleanup-"+tenant,now,now);j.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",owner,tenant,owner+"@example.com",now,now);return new Scope(tenant,owner);}private record Scope(String tenant,String owner){}
}
