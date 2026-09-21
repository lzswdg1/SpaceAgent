package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.EmbeddingBatchExecutor;
import com.spaceagent.platform.knowledge.application.KnowledgeIndexBuildCoordinator;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.PgVectorIndexGateway;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production API/index/retrieval wiring, actual PostgreSQL vectors/FTS; only the model is fake. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlatformPgVectorKnowledgePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static final Path ROOT = temporary();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword); r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> true); r.add("spring.flyway.locations", () -> "classpath:db/platform-server,classpath:db/platform-runtime");
        r.add("platform.persistence", () -> "postgres"); r.add("platform.security.allow-insecure-local", () -> true);
        r.add("platform.runtime.coordination.enabled", () -> false); r.add("platform.inference.health-probes-enabled", () -> false);
        r.add("platform.inference.allowed-provider-hosts", () -> "example.com");
        r.add("platform.knowledge.vector-store.mode", () -> "pgvector");
        r.add("platform.knowledge.indexing.intake-enabled", () -> true); r.add("platform.knowledge.indexing.worker-enabled", () -> false);
        r.add("platform.knowledge.index-content-root", ROOT::toString); r.add("platform.knowledge.url-content-root", () -> ROOT.resolve("urls").toString());
        r.add("platform.knowledge.url-refresh.enabled", () -> false); r.add("platform.identity.cleanup.worker-enabled", () -> false);
        r.add("platform.identity.user-cleanup.worker-enabled", () -> false); r.add("platform.tooling.mcp.registry.worker-enabled", () -> false);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired VectorIndexGateway vectors; @Autowired ObjectProvider<MilvusClientV2> milvus;
    @Autowired KnowledgeIndexJobRepository jobs; @Autowired KnowledgeIndexBuildCoordinator build;
    @MockitoBean EmbeddingBatchExecutor embedding; // No paid or outside HTTP calls are possible.

    @Test void selectedPgvectorBuildsAndRetrievesThroughCurrentPublicApisWithoutMilvus() throws Exception {
        assertThat(vectors).isInstanceOf(PgVectorIndexGateway.class); assertThat(milvus.getIfAvailable()).isNull();
        assertThat(jdbc.queryForObject("SELECT mode FROM platform_knowledge_vector_backend", String.class)).isEqualTo("pgvector");
        when(embedding.execute(any(), anyString(), anyInt(), anyList())).thenAnswer(a -> {
            List<String> inputs = a.getArgument(3);
            return new EmbeddingBatchExecutor.Outcome(EmbeddingBatchExecutor.Status.SUCCEEDED,
                    inputs.stream().map(t -> List.of(1.0, .5)).toList(), 3L, null);
        });
        String user = register(), outsider = register();
        String base = send(post("/api/v1/knowledge/bases"), user, Map.of("scope", "PERSONAL", "name", "Fixture"), 201).at("/base/id").asText();
        String provider = send(post("/api/v1/model-providers"), user, Map.of("name", "Fixture", "type", "openai", "baseUrl", "https://example.com/v1",
                "apiKey", "fixture-secret", "models", List.of(Map.of("modelId", "embedding-fixture", "displayName", "Fixture"))), 201).path("id").asText();
        String space = send(post("/api/v1/knowledge/bases/" + base + "/embedding-spaces"), user, Map.of("providerId", provider, "modelId", "embedding-fixture",
                "modelRevision", "r1", "dimensions", 2, "preprocessingHash", "a".repeat(64)), 201).at("/space/id").asText();
        String content = "企业知识库使用权限过滤，保护用户隐私。durable repository checkpoint evidence";
        var imported = send(post("/api/v1/knowledge/bases/" + base + "/indexed-documents").header("Idempotency-Key", "intake")
                .param("name", "Fixture").param("spaceId", space).contentType("text/plain").content(content), user, null, 202);
        var job = jobs.claimNext("fixture", 90).orElseThrow();
        assertThat(job.id()).isEqualTo(imported.path("jobId").asText());
        assertThat(build.execute(job.lease()).progress().state()).isEqualTo(KnowledgeIndexJob.State.COMPLETED);
        var query = Map.of("baseIds", List.of(base), "query", "知识库 checkpoint", "topK", 5, "maxContextTokens", 1024);
        var result = send(post("/api/v1/knowledge/collections/retrieve").header("Idempotency-Key", "query"), user, query, 200);
        assertThat(result.path("hits")).hasSize(1); assertThat(result.at("/hits/0/content").asText()).isEqualTo(content);
        send(post("/api/v1/knowledge/collections/retrieve").header("Idempotency-Key", "foreign"), outsider, query, 404);
        verify(embedding, times(2)).execute(any(), anyString(), anyInt(), anyList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_pgvector_entries", Long.class)).isEqualTo(1);
    }
    String register() throws Exception {
        return send(post("/api/v1/auth/register"), null, Map.of("username", UUID.randomUUID() + "@example.test", "password", "password123", "displayName", "Fixture"), 200).path("token").asText();
    }
    JsonNode send(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return json.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    static Path temporary() { try { return Files.createTempDirectory("pgvector-knowledge-fixture-").toRealPath(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @AfterAll static void cleanup() throws Exception { try (var paths = Files.walk(ROOT)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
}
