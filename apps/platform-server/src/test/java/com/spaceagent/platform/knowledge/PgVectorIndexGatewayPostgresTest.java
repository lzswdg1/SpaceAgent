package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.VectorIndexGateway.*;
import com.spaceagent.platform.knowledge.infrastructure.PgVectorIndexGateway;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PgVectorIndexGatewayPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static PgVectorIndexGateway gateway;
    @BeforeAll static void schema() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();
        jdbc = new JdbcTemplate(ds); gateway = new PgVectorIndexGateway(jdbc, 5);
    }
    static Space space(String schema) { return new Space(UUID.randomUUID().toString(), "a".repeat(64), 2, schema); }
    static Entry entry(String scope, String base, String generation, String chunk, String text, List<Double> vector) {
        return new Entry(scope, base, "doc-" + chunk, generation, chunk,
                com.spaceagent.platform.knowledge.application.KnowledgeIndexBuildCoordinator.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), vector, text);
    }

    @Test void nativeVectorRoundTripScopesSchemaAndSpaceAndGenerationDeletion() {
        var s = space("hybrid_v2"); var other = space("hybrid_v2");
        var dense = new Space(s.id(), s.fingerprint(), 2, "dense_v1");
        assertThat(gateway.spaceExists(s)).isFalse();
        gateway.ensureSpace(s); gateway.ensureSpace(s); gateway.ensureSpace(other); gateway.ensureSpace(dense);
        var a = entry("org:one", "base", "gen", "a", "企业知识库权限保护隐私", List.of(1.0, 0.0));
        var b = entry("org:two", "base", "gen", "b", "企业知识库权限保护隐私", List.of(1.0, 0.0));
        var c = entry("org:one", "other-base", "gen", "c", "企业知识库权限保护隐私", List.of(1.0, 0.0));
        var d = entry("org:one", "base", "other-gen", "d", "durable repository checkpoints", List.of(0.0, 1.0));
        gateway.upsertBatch(s, List.of(a, b, c, d)); gateway.upsertBatch(s, List.of(a));
        gateway.upsertBatch(other, List.of(a)); gateway.upsertBatch(dense, List.of(a));
        assertThat(gateway.verifyBatch(s, List.of(a, b, c, d))).isTrue();
        var scope = new Scope("org:one", "base", List.of("gen"));
        assertThat(gateway.search(s, scope, List.of(1.0, 0.0), 10)).extracting(Match::chunkId).containsExactly("a");
        assertThat(gateway.lexicalSearch(s, scope, "知识库 隐私", 10)).extracting(Match::chunkId).containsExactly("a");
        assertThat(gateway.lexicalSearch(s, new Scope("org:one", "base", List.of("other-gen")), "repository checkpoints", 10))
                .extracting(Match::chunkId).containsExactly("d");
        assertThat(gateway.lexicalSearch(dense, scope, "知识库", 10)).isEmpty();
        gateway.deleteGeneration(s, scope, "gen"); assertThat(gateway.generationDeleted(s, scope, "gen")).isTrue();
        assertThat(gateway.search(s, scope, List.of(1.0, 0.0), 10)).isEmpty();
        assertThat(gateway.generationDeleted(s, new Scope("org:two", "base", List.of("gen")), "gen")).isFalse();
        assertThat(gateway.verifyBatch(other, List.of(a))).isTrue(); assertThat(gateway.verifyBatch(dense, List.of(a))).isTrue();
        // Tombstone sweeps can detect and remove a late replica write without touching other scopes.
        gateway.upsertBatch(s, List.of(a)); assertThat(gateway.generationDeleted(s, scope, "gen")).isFalse();
        gateway.deleteGeneration(s, scope, "gen"); assertThat(gateway.generationDeleted(s, scope, "gen")).isTrue();
    }

    @Test void conflictingPayloadCannotOverwriteAndEntireBatchRollsBack() {
        var s = space("dense_v1"); gateway.ensureSpace(s);
        var a = entry("user:one", "base", "gen", "a", "original", List.of(1.0, 0.0));
        var fresh = entry("user:one", "base", "gen", "fresh", "fresh", List.of(0.0, 1.0));
        var conflict = entry("user:one", "base", "gen", "a", "changed", List.of(0.0, 1.0));
        gateway.upsertBatch(s, List.of(a));
        assertThatThrownBy(() -> gateway.upsertBatch(s, List.of(fresh, conflict))).hasMessageContaining("IMMUTABLE_ENTRY_CONFLICT");
        assertThat(gateway.verifyBatch(s, List.of(a))).isTrue(); assertThat(gateway.verifyBatch(s, List.of(fresh))).isFalse();
    }

    @Test void simultaneousDifferentPayloadsHaveOneWinner() throws Exception {
        var s = space("dense_v1"); gateway.ensureSpace(s);
        var first = entry("user:one", "base", "gen", "a", "first", List.of(1.0, 0.0));
        var second = entry("user:one", "base", "gen", "a", "second", List.of(0.0, 1.0));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> write(start, s, first)); var b = executor.submit(() -> write(start, s, second)); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }
    static boolean write(CountDownLatch start, Space space, Entry entry) throws Exception {
        start.await(); try { gateway.upsertBatch(space, List.of(entry)); return true; }
        catch (IllegalStateException conflict) { assertThat(conflict).hasMessageContaining("IMMUTABLE_ENTRY_CONFLICT"); return false; }
    }

    @Test void validatesGeometryBoundsHashesAndParameterizedLexicalText() {
        var s = space("hybrid_v2"); gateway.ensureSpace(s); var scope = new Scope("user:one", "base", List.of("gen"));
        assertThatThrownBy(() -> gateway.ensureSpace(new Space(s.id(), "b".repeat(64), 2, s.schema()))).hasMessageContaining("SCHEMA_MISMATCH");
        assertThatThrownBy(() -> gateway.validateSpace(new Space("large", "a".repeat(64), 16001))).hasMessageContaining("DIMENSIONS_UNSUPPORTED");
        assertThatThrownBy(() -> gateway.search(s, scope, List.of(0.0, 0.0), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.search(s, scope, List.of(Double.NaN, 1.0), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.search(s, scope, List.of(1.0), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.search(s, scope, List.of(1.0, 0.0), 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.deleteGeneration(s, scope, "foreign")).isInstanceOf(IllegalArgumentException.class);
        var bad = new Entry("user:one", "base", "doc", "gen", "a", "a".repeat(64), List.of(1.0, 0.0), "wrong hash");
        assertThatThrownBy(() -> gateway.upsertBatch(s, List.of(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(gateway.lexicalSearch(s, scope, "' | ; DROP TABLE unrelated --", 1)).isEmpty();
        assertThat(gateway.spaceExists(s)).isTrue();
    }

    @Test void boundedScopedNativeSearchPerformanceSmoke() {
        var s = new Space(UUID.randomUUID().toString(), "c".repeat(64), 128, "hybrid_v2"); gateway.ensureSpace(s);
        var vector = new ArrayList<>(Collections.nCopies(128, .01)); vector.set(0, 1.0);
        for (int batch = 0; batch < 4; batch++) {
            var rows = new ArrayList<Entry>();
            for (int i = 0; i < 64; i++) rows.add(entry(i % 2 == 0 ? "org:one" : "org:two", "base", "gen", "c" + (batch * 64 + i), "企业知识库 durable checkpoint " + i, vector));
            gateway.upsertBatch(s, rows);
        }
        var durations = new ArrayList<Long>(); var scope = new Scope("org:one", "base", List.of("gen"));
        for (int i = 0; i < 23; i++) {
            long start = System.nanoTime(); assertThat(gateway.search(s, scope, vector, 5)).hasSize(5);
            assertThat(gateway.lexicalSearch(s, scope, "知识库 checkpoint", 5)).hasSize(5);
            if (i >= 3) durations.add((System.nanoTime() - start) / 1_000_000);
        }
        var sorted = durations.stream().sorted().toList();
        System.out.printf("PGVECTOR_PERF_FIXTURE rows=256 dimensions=128 pairs=20 p50_ms=%d p95_ms=%d%n", sorted.get(9), sorted.get(18));
        assertThat(sorted.get(18)).isLessThan(5000);
    }
}
