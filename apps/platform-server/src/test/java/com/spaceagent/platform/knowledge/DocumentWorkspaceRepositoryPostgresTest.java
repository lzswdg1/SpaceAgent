package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresDocumentWorkspaceRepository;
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
class DocumentWorkspaceRepositoryPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("document_workspace").withUsername("spaceagent").withPassword("spaceagent");
    private static DriverManagerDataSource dataSource;
    @BeforeAll static void migrate(){dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(dataSource).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();}

    @Test void quotaCasFileEvidenceAndRestartAreAtomic(){var scope=seed("atomic");var repo=repository();Instant now=repo.currentTime();var workspace=workspace(scope,now);repo.insert(workspace,"create-1");String hash="sha256:"+"a".repeat(64);var claim=repo.claimMutation(request(scope,workspace,"call-1","write-1",hash,DocumentWorkspaceOperation.Type.WRITE,"notes/a.md",120,now));assertThat(claim.type()).isEqualTo(DocumentWorkspaceRepository.ClaimType.CLAIMED);assertThat(claim.workspace().usage()).isEqualTo(new DocumentWorkspace.Usage(1,120));var completed=repo.completeMutation(scope.tenant,claim.operation().id(),120,hash,now.plusSeconds(1)).orElseThrow();assertThat(completed.operation().state()).isEqualTo(DocumentWorkspaceOperation.State.SUCCEEDED);assertThat(repository().findById(scope.tenant,workspace.id())).get().extracting(DocumentWorkspace::usage).isEqualTo(new DocumentWorkspace.Usage(1,120));assertThat(repo.claimMutation(request(scope,workspace,"call-1","write-1",hash,DocumentWorkspaceOperation.Type.WRITE,"notes/a.md",120,now)).type()).isEqualTo(DocumentWorkspaceRepository.ClaimType.REPLAY);}

    @Test void unknownRetainsReservationAndBlocksPathWithoutBlindRetry(){var scope=seed("unknown");var repo=repository();Instant now=repo.currentTime();var workspace=workspace(scope,now);repo.insert(workspace,"create-2");String hash="sha256:"+"b".repeat(64);var claim=repo.claimMutation(request(scope,workspace,"call-2","write-2",hash,DocumentWorkspaceOperation.Type.WRITE,"draft.md",200,now));repo.markMutationUnknown(scope.tenant,claim.operation().id(),"SANDBOX_OUTCOME_UNKNOWN",now.plusSeconds(1));assertThat(repo.findById(scope.tenant,workspace.id())).get().extracting(DocumentWorkspace::usage).isEqualTo(new DocumentWorkspace.Usage(1,200));assertThat(repo.claimMutation(request(scope,workspace,"call-2","write-2",hash,DocumentWorkspaceOperation.Type.WRITE,"draft.md",200,now)).type()).isEqualTo(DocumentWorkspaceRepository.ClaimType.UNKNOWN);assertThat(repo.claimMutation(request(scope,workspace,"call-3","write-3",hash,DocumentWorkspaceOperation.Type.WRITE,"draft.md",200,now)).type()).isEqualTo(DocumentWorkspaceRepository.ClaimType.BUSY);}

    @Test void v1067UpgradesIdempotently(){String schema="document_workspace_upgrade";Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1066")).load().migrate();var jdbc=new JdbcTemplate(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));assertThat(jdbc.queryForObject("SELECT to_regclass('platform_document_workspaces') IS NULL",Boolean.class)).isTrue();var flyway=Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();flyway.migrate();flyway.migrate();assertThat(jdbc.queryForObject("SELECT to_regclass('platform_document_workspace_operations') IS NOT NULL",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("1098");}

    private static PostgresDocumentWorkspaceRepository repository(){return new PostgresDocumentWorkspaceRepository(new JdbcTemplate(dataSource),new DataSourceTransactionManager(dataSource));}
    private static DocumentWorkspace workspace(Scope s,Instant now){String id=UUID.randomUUID().toString();return new DocumentWorkspace(id,s.tenant,DocumentWorkspace.ScopeType.USER,s.owner,s.owner,"Docs","document-workspace:"+id,new DocumentWorkspace.Quota(10,1_048_576),new DocumentWorkspace.Usage(0,0),DocumentWorkspace.State.ACTIVE,1,now,now,null);}
    private static DocumentWorkspaceRepository.MutationRequest request(Scope s,DocumentWorkspace w,String call,String key,String hash,DocumentWorkspaceOperation.Type type,String path,long bytes,Instant now){return new DocumentWorkspaceRepository.MutationRequest(UUID.randomUUID().toString(),s.tenant,w.id(),s.owner,"run-1","step-1",call,key,hash,type,path,bytes,now);}
    private static Scope seed(String suffix){JdbcTemplate j=new JdbcTemplate(dataSource);String tenant=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());j.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Tenant "+suffix,"document-workspace-"+tenant,now,now);j.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",owner,tenant,owner+"@example.com",now,now);return new Scope(tenant,owner);}private record Scope(String tenant,String owner){}
}
