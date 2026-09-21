package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.infrastructure.KnowledgeVectorBackendSelection;
import com.spaceagent.platform.support.PostgresSchemaDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeVectorBackendSelectionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Test void firstChoiceIsDurableNoneDoesNotResetAndWorkerMismatchFails() {
        var ds = PostgresSchemaDataSource.forSchema(PG, "fresh");
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        new KnowledgeVectorBackendSelection(jdbc, "pgvector").validate();
        new KnowledgeVectorBackendSelection(jdbc, "none").validate();
        new KnowledgeVectorBackendSelection(jdbc, "pgvector").validate();
        assertThatThrownBy(() -> new KnowledgeVectorBackendSelection(jdbc, "milvus").validate()).hasMessageContaining("BACKEND_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT mode FROM platform_knowledge_vector_backend", String.class)).isEqualTo("pgvector");
    }
    @Test void v1097UpgradePreservesOldGenerationAndPinsMilvus() {
        var ds = PostgresSchemaDataSource.forSchema(PG, "upgrade");
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server", "classpath:db/platform-runtime").target("1097").load().migrate();
        var jdbc = new JdbcTemplate(ds); String base = UUID.randomUUID().toString(), doc = UUID.randomUUID().toString(), space = UUID.randomUUID().toString(), gen = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES('org','Org','org','ACTIVE',now(),now())");
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES('owner','org','fixture@test.local','Owner',now(),now())");
        jdbc.update("INSERT INTO platform_knowledge_bases(id,scope,owner_id,name,description,state,revision,created_at,updated_at) VALUES(?,'PERSONAL','owner','Base','','ACTIVE',1,now(),now())", base);
        jdbc.update("INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at) VALUES(?,'owner','Doc','text/plain','inline:test','UPLOADED',now(),now())", doc);
        jdbc.update("INSERT INTO platform_knowledge_document_scopes VALUES(?,?,'owner','EXPLICIT',now())", doc, base);
        jdbc.update("INSERT INTO platform_knowledge_embedding_spaces VALUES(?,?,'provider','model','r1',?,2,?,?,'owner',now())", space, base, "a".repeat(64), "b".repeat(64), "c".repeat(64));
        jdbc.update("INSERT INTO platform_knowledge_index_generations VALUES(?,?,?,?,?,?,?,?,'owner',now())", gen, base, doc, space, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64));
        var latest = Flyway.configure().dataSource(ds).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1); assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT id FROM platform_knowledge_index_generations", String.class)).isEqualTo(gen);
        new KnowledgeVectorBackendSelection(jdbc, "milvus").validate();
        assertThatThrownBy(() -> new KnowledgeVectorBackendSelection(jdbc, "pgvector").validate()).hasMessageContaining("BACKEND_MISMATCH");
    }
}
