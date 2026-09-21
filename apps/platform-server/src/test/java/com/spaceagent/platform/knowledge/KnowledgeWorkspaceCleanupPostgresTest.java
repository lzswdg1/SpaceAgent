package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.KnowledgeCleanupApplicationApi;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.*;
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
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class KnowledgeWorkspaceCleanupPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("knowledge_cleanup").withUsername("spaceagent").withPassword("spaceagent");
    private static DriverManagerDataSource dataSource;
    @BeforeAll static void migrate(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();}
    @Test void userCleanupDeletesBytesBeforeMetadata(){Scope s=seed("user");var repo=repo();var workspace=workspace(s,DocumentWorkspace.ScopeType.USER);repo.insert(workspace,"create");AtomicBoolean bytes=new AtomicBoolean();DocumentWorkspaceStorageGateway storage=new DocumentWorkspaceStorageGateway(){public void provision(DocumentWorkspace ignored){}public void delete(DocumentWorkspace value){assertThat(repo.findById(s.tenant,value.id())).isPresent();bytes.set(true);}};var result=new PostgresKnowledgeCleanupService(new JdbcTemplate(dataSource),repo,storage).cleanupUser(s.owner);assertThat(result.blocked()).isFalse();assertThat(bytes).isTrue();assertThat(repo.findById(s.tenant,workspace.id())).isEmpty();}
    @Test void unknownMutationBlocksCleanupWithoutDeletingBytes(){Scope s=seed("blocked");var repo=repo();var workspace=workspace(s,DocumentWorkspace.ScopeType.USER);repo.insert(workspace,"create");String hash="sha256:"+"a".repeat(64);var claim=repo.claimMutation(new DocumentWorkspaceRepository.MutationRequest(UUID.randomUUID().toString(),s.tenant,workspace.id(),s.owner,"run","step","call","key",hash,DocumentWorkspaceOperation.Type.WRITE,"a.md",5,repo.currentTime()));repo.markMutationUnknown(s.tenant,claim.operation().id(),"SANDBOX_OUTCOME_UNKNOWN",repo.currentTime());AtomicBoolean bytes=new AtomicBoolean();DocumentWorkspaceStorageGateway storage=new DocumentWorkspaceStorageGateway(){public void provision(DocumentWorkspace ignored){}public void delete(DocumentWorkspace ignored){bytes.set(true);}};KnowledgeCleanupApplicationApi.CleanupResult result=new PostgresKnowledgeCleanupService(new JdbcTemplate(dataSource),repo,storage).cleanupUser(s.owner);assertThat(result.blocked()).isTrue();assertThat(bytes).isFalse();assertThat(repo.findById(s.tenant,workspace.id())).isPresent();}
    @Test void v1068UpgradesAndBackfillsCleanupStepConstraint(){String schema="knowledge_cleanup_upgrade";Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1067")).load().migrate();var flyway=Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();flyway.migrate();flyway.migrate();var jdbc=new JdbcTemplate(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("1098");}
    private static PostgresDocumentWorkspaceRepository repo(){return new PostgresDocumentWorkspaceRepository(new JdbcTemplate(dataSource),new DataSourceTransactionManager(dataSource));}
    private static DocumentWorkspace workspace(Scope s,DocumentWorkspace.ScopeType type){Instant now=Instant.now();String id=UUID.randomUUID().toString(),scope=type==DocumentWorkspace.ScopeType.USER?s.owner:s.tenant;return new DocumentWorkspace(id,s.tenant,type,scope,s.owner,"Docs","document-workspace:"+id,new DocumentWorkspace.Quota(10,1048576),new DocumentWorkspace.Usage(0,0),DocumentWorkspace.State.ACTIVE,1,now,now,null);}
    private static Scope seed(String suffix){JdbcTemplate j=new JdbcTemplate(dataSource);String tenant=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());j.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Tenant "+suffix,"knowledge-cleanup-"+tenant,now,now);j.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",owner,tenant,owner+"@example.com",now,now);return new Scope(tenant,owner);}private record Scope(String tenant,String owner){}
}
