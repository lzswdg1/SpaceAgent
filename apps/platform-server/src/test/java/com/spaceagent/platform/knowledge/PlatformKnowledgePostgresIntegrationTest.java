package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.ProcessKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.application.KnowledgeApplicationService;
import com.spaceagent.platform.knowledge.application.KnowledgeChunkingService;
import com.spaceagent.platform.knowledge.infrastructure.DeterministicKnowledgeEmbeddingGateway;
import com.spaceagent.platform.knowledge.infrastructure.InlineOrReferenceDocumentParser;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.infrastructure.persistence.PostgresKnowledgeDocumentRepository;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** PostgreSQL proof for V10 processing, embedding metadata, and retrieval. */
@Testcontainers(disabledWithoutDocker = true)
class PlatformKnowledgePostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_knowledge_core")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
    }

    @Test
    void processingAndRetrievalSurvivePostgresRoundTrip() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        jdbc.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "tenant-1", "Tenant 1", "tenant-1", "ACTIVE",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users (id, tenant_id, external_id, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "owner-1", "tenant-1", "owner@example.com", "Owner",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, "tenant-1", "owner-1", "OWNER", "ACTIVE",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        KnowledgeApplicationService service = new KnowledgeApplicationService(
                new PostgresKnowledgeDocumentRepository(jdbc),
                new PostgresKnowledgeChunkRepository(jdbc, new ObjectMapper()),
                new UuidGenerator(),
                (TimeProvider) () -> NOW,
                new InlineOrReferenceDocumentParser(),
                new DeterministicKnowledgeEmbeddingGateway(),
                new KnowledgeChunkingService(120, 20), user -> true);
        var document = service.createDocument(new CreateKnowledgeDocumentCommand(
                "owner-1", "architecture.md", "text/markdown", "inline:Runtime owns checkpoints. ".repeat(20)));
        var processed = service.process(new ProcessKnowledgeDocumentCommand(
                document.id(), "owner-1", null));
        var retrieval = service.retrieve(new KnowledgeRetrievalCommand(
                "owner-1", List.of(document.id()), "runtime checkpoints", 3));

        assertEquals("READY", processed.document().status().name());
        assertFalse(processed.chunks().isEmpty());
        assertFalse(retrieval.matches().isEmpty());
        Number embedded = jdbc.queryForObject("""
                SELECT count(*) FROM platform_knowledge_chunks
                WHERE document_id = ? AND embedding_dimensions = 16 AND embedding_vector <> '[]'
                """, Number.class, document.id());
        assertEquals(processed.chunks().size(), embedded.intValue());
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
