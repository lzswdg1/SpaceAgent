package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeResourceObservationQueries;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class RuntimeResourceObservationsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Test void bulkEvidenceSqlMatchesOnlyExactActiveGenerationScopeAndHash() {
        var base=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        new JdbcTemplate(base).execute("CREATE SCHEMA bulk_evidence");
        var ds=com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(PG,"bulk_evidence");
        var jdbc=new JdbcTemplate(ds);
        // Isolated SQL projection fixture uses the production column types without
        // a Milvus/Tika service or weakening the production ownership constraints.
        jdbc.execute("CREATE TABLE platform_knowledge_document_heads(base_id varchar,document_id varchar,active_generation_id varchar,state varchar)");
        jdbc.execute("CREATE TABLE platform_knowledge_documents(id varchar,name varchar,status varchar)");
        jdbc.execute("CREATE TABLE platform_knowledge_document_scopes(document_id varchar,base_id varchar)");
        jdbc.execute("CREATE TABLE platform_knowledge_generation_sources(generation_id varchar,document_revision bigint)");
        jdbc.execute("CREATE TABLE platform_knowledge_generation_chunks(generation_id varchar,chunk_id varchar,ordinal integer,content varchar,content_hash varchar,metadata jsonb)");
        jdbc.update("INSERT INTO platform_knowledge_document_heads VALUES('base','doc','gen','ACTIVE')");
        jdbc.update("INSERT INTO platform_knowledge_documents VALUES('doc','Title','READY')");
        jdbc.update("INSERT INTO platform_knowledge_document_scopes VALUES('doc','base')");
        jdbc.update("INSERT INTO platform_knowledge_generation_sources VALUES('gen',1)");
        jdbc.update("INSERT INTO platform_knowledge_generation_chunks VALUES('gen','chunk',1,'Evidence','hash','{}')");
        var repo=new com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeRetrievalIndexRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
        var valid=new com.spaceagent.platform.knowledge.domain.KnowledgeRetrievalIndexRepository.EvidenceKey("base","gen","chunk","hash");
        var foreign=new com.spaceagent.platform.knowledge.domain.KnowledgeRetrievalIndexRepository.EvidenceKey("other","gen","chunk","hash");
        var wrong=new com.spaceagent.platform.knowledge.domain.KnowledgeRetrievalIndexRepository.EvidenceKey("base","gen","chunk","wrong");
        assertThat(repo.evidenceBatch(java.util.List.of(valid,foreign,wrong))).containsOnlyKeys(valid);
        jdbc.update("UPDATE platform_knowledge_document_heads SET state='DELETED'");
        assertThat(repo.evidenceBatch(java.util.List.of(valid))).isEmpty();
    }
    @Test void observationsAreScopedNullableAndSnapshotsAreNotRepeatedStorageConsumption() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load().migrate();
        var jdbc=new JdbcTemplate(ds);Instant now=Instant.now();var time=Timestamp.from(now);
        String tenant=UUID.randomUUID().toString(),user=UUID.randomUUID().toString(),agent=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",tenant,"Fixture",tenant,time,time);
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Fixture',?,?)",user,tenant,user+"@example.com",time,time);
        jdbc.update("INSERT INTO platform_agent_definitions(id,owner_id,tenant_id,name,status,revision,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,?,?)",agent,user,tenant,agent,time,time);
        jdbc.update("INSERT INTO platform_agent_runs(id,tenant_id,agent_id,owner_id,conversation_id,state,created_at,updated_at) VALUES(?,?,?,?,?,'IN_PROGRESS',?,?)",run,tenant,agent,user,UUID.randomUUID().toString(),time,time);
        for(int i=1;i<=2;i++)jdbc.update("INSERT INTO platform_run_events(id,agent_run_id,sequence_number,event_type,payload,execution_cursor,created_at) VALUES(?,?,?,'RESOURCE_OBSERVED',?::jsonb,'{}'::jsonb,?)",
                UUID.randomUUID().toString(),run,i,"{\"executionId\":\"exec-"+i+"\",\"workspaceId\":\"workspace\",\"resourceMetrics\":{\"cpuUsageNanos\":123,\"maxObservedMemoryBytes\":456,\"workspaceApparentBytes\":"+(i*100)+"}}",Timestamp.from(now.plusMillis(i)));
        var queries=new PostgresRuntimeResourceObservationQueries(jdbc);
        var result=queries.summary(tenant,user,now.minusSeconds(1),now.plusSeconds(2));
        assertThat(result.observations()).isEqualTo(2);assertThat(result.knownCpuUsageNanos()).isEqualByComparingTo("246");
        assertThat(result.latestObservedWorkspaceApparentBytes()).isEqualByComparingTo("200");
        assertThat(result.knownNetworkRxBytes()).isNull();assertThat(result.missingNetworkObservations()).isEqualTo(2);
        assertThat(queries.summary("foreign",user,now.minusSeconds(1),now.plusSeconds(2)).knownCpuUsageNanos()).isNull();
        assertThat(queries.summary(tenant,"other",now.minusSeconds(1),now.plusSeconds(2)).observations()).isZero();
    }
}
