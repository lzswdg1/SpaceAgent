package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeIndexCatalogRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeIndexCatalogPostgresTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("index_catalog").withUsername("spaceagent").withPassword("fixture-password");
    static JdbcTemplate jdbc;
    static DriverManagerDataSource ds;
    static String base;
    static final Instant NOW=Instant.parse("2026-09-14T00:00:00Z");
    @BeforeAll static void upgrade() {
        ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").target("1086").load().migrate();
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES ('org','org','org','ACTIVE',?,?)",Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES ('owner','org','owner@test.local','Owner',?,?)",Timestamp.from(NOW),Timestamp.from(NOW));
        for(String id:List.of("document-one","document-two"))jdbc.update("""
                INSERT INTO platform_knowledge_documents(id,owner_id,name,content_type,storage_location,status,created_at,updated_at)
                VALUES (?,'owner',?,'text/plain','inline:private','READY',?,?)
                """,id,id,Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_knowledge_chunks(id,document_id,sequence_number,content,embedding_model,embedding_dimensions,embedding_vector,created_at)
                VALUES ('chunk','document-one',0,'private','unknown-provider-model',2,'[1.0,2.0]',?)
                """,Timestamp.from(NOW));
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();
        flyway.migrate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
        base=jdbc.queryForObject("SELECT base_id FROM platform_knowledge_document_scopes WHERE document_id='document-one'",String.class);
    }
    @Test void legacyBackfillIsPrivateAndDoesNotGuessTheEmbeddingSpace() {
        assertThat(jdbc.queryForObject("SELECT scope FROM platform_knowledge_bases WHERE id=?",String.class,base)).isEqualTo("PERSONAL");
        assertThat(jdbc.queryForObject("SELECT organization_id FROM platform_knowledge_bases WHERE id=?",String.class,base)).isNull();
        assertThat(jdbc.queryForObject("SELECT base_id FROM platform_knowledge_document_scopes WHERE document_id='document-two'",String.class)).isEqualTo(base);
        assertThat(jdbc.queryForObject("SELECT embedding_vector FROM platform_knowledge_chunks WHERE id='chunk'",String.class)).isEqualTo("[1.0,2.0]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_embedding_spaces WHERE provider_id='unknown-provider-model'",Integer.class)).isZero();
    }
    @Test void spacesAndGenerationsHaveStableIdentityAndScopedForeignKeys() {
        var repo=new PostgresKnowledgeIndexCatalogRepository(jdbc);
        var space=space("space-one","a");
        assertThat(repo.insertSpaceIfAbsent(space)).isEqualTo(space);
        assertThat(repo.insertSpaceIfAbsent(space("retry-space","a"))).isEqualTo(space);
        var other=repo.insertSpaceIfAbsent(space("space-two","b"));
        assertThat(other.dimensions()).isEqualTo(space.dimensions());
        assertThatThrownBy(()->space.requireCompatible(other)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->space.validateVector(List.of(Double.NaN,1.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->space.validateVector(List.of(1.0))).isInstanceOf(IllegalArgumentException.class);
        space.validateVector(List.of(0.5,1.0));
        var generation=new KnowledgeIndexGeneration("generation-one",base,"document-one",space.id(),"c".repeat(64),"d".repeat(64),"e".repeat(64),"f".repeat(64),"owner",NOW);
        assertThat(repo.insertGenerationIfAbsent(generation)).isEqualTo(generation);
        assertThat(repo.insertGenerationIfAbsent(generation)).isEqualTo(generation);
        assertThat(repo.findSpace("other-base",space.id())).isEmpty();
        assertThatThrownBy(()->repo.insertGenerationIfAbsent(new KnowledgeIndexGeneration("bad",base,"missing-doc",space.id(),"c".repeat(64),"d".repeat(64),"e".repeat(64),"0".repeat(64),"owner",NOW)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var restarted=new PostgresKnowledgeIndexCatalogRepository(new JdbcTemplate(ds));
        assertThat(restarted.listGenerations(base,"document-one",0,50)).containsExactly(generation);
        assertThat(repo.insertScopeIfAbsent(new KnowledgeDocumentScope("document-one",base,"owner","EXPLICIT",NOW)).provenance()).isEqualTo("LEGACY_PRIVATE");
    }
    private static KnowledgeEmbeddingSpace space(String id,String hash) {
        return new KnowledgeEmbeddingSpace(id,base,"provider","model",hash,"a".repeat(64),2,"b".repeat(64),hash.repeat(64),"owner",NOW);
    }
}
