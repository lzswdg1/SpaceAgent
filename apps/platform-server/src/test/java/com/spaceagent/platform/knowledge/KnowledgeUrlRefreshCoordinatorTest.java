package com.spaceagent.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.application.KnowledgeChunkingService;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlActivationService;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlContentParser;
import com.spaceagent.platform.knowledge.application.KnowledgeUrlRefreshCoordinator;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlEvidence;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import com.spaceagent.platform.knowledge.infrastructure.DeterministicKnowledgeEmbeddingGateway;
import com.spaceagent.platform.knowledge.infrastructure.FileSystemKnowledgeUrlContentStore;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.infrastructure.memory.InMemoryKnowledgeUrlRepository;
import com.spaceagent.platform.tooling.api.SecureUrlFetchApplicationApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeUrlRefreshCoordinatorTest {
    @Test void inactiveActorNeverStartsNetworkFetchOrEmbedding() {
        var calls=new AtomicInteger();
        var coordinator=new KnowledgeUrlRefreshCoordinator(urls,command->{calls.incrementAndGet();return result("body".getBytes(),200);},store,
                new KnowledgeUrlContentParser(new ObjectMapper()),new KnowledgeChunkingService(100,10),embedding,
                new KnowledgeUrlActivationService(urls,chunks,documents),()->UUID.randomUUID().toString(),
                (tenant,user,write)->{throw new com.spaceagent.shared.exception.BusinessException("Inactive actor",org.springframework.http.HttpStatus.FORBIDDEN,"EXECUTION_ACTOR_UNAVAILABLE");});
        coordinator.runOnce("worker",300);
        assertThat(calls).hasValue(0);
        assertThat(embedding.calls).hasValue(0);
    }
    @TempDir
    Path root;

    private AtomicReference<Instant> time;
    private AtomicInteger ids;
    private InMemoryKnowledgeUrlRepository urls;
    private InMemoryKnowledgeDocumentRepository documents;
    private InMemoryKnowledgeChunkRepository chunks;
    private CountingEmbedding embedding;
    private FileSystemKnowledgeUrlContentStore store;
    private String jobId;
    private String documentId;

    @BeforeEach
    void setUp() {
        time = new AtomicReference<>(Instant.parse("2026-09-09T00:00:00Z"));
        ids = new AtomicInteger();
        urls = new InMemoryKnowledgeUrlRepository(time::get);
        documents = new InMemoryKnowledgeDocumentRepository();
        chunks = new InMemoryKnowledgeChunkRepository();
        embedding = new CountingEmbedding();
        store = new FileSystemKnowledgeUrlContentStore(root);
        jobId = UUID.randomUUID().toString();
        documentId = UUID.randomUUID().toString();
        documents.save(new KnowledgeDocument(
                documentId, "owner", "URL", "text/html", "managed:pending",
                KnowledgeDocumentStatus.UPLOADED, time.get(), time.get()));
        var policy = new KnowledgeUrlJob.RefreshPolicy(
                KnowledgeUrlJob.RefreshMode.PERIODIC, 300, true, 2, 10_000);
        urls.insertJob(new KnowledgeUrlJob(
                jobId, "tenant", "owner", documentId,
                "https://docs.example.com/", "https://docs.example.com", policy,
                KnowledgeUrlJob.State.ACTIVE, 1, time.get(), time.get(), time.get(), null));
    }

    @Test
    void fetchesSanitizesEmbedsStoresAndSkipsUnchangedContent() {
        byte[] body = "<html><script>steal()</script><body>Hello durable knowledge</body></html>"
                .getBytes(StandardCharsets.UTF_8);
        var coordinator = coordinator(command -> result(body, 200));

        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        assertThat(chunks.findByDocumentId(documentId)).singleElement()
                .satisfies(chunk -> assertThat(chunk.content())
                        .contains("Hello durable knowledge").doesNotContain("steal"));
        assertThat(documents.findById(documentId).orElseThrow().status())
                .isEqualTo(KnowledgeDocumentStatus.READY);
        var version = urls.contentVersions("tenant", jobId).getFirst();
        assertThat(version.state()).isEqualTo(KnowledgeUrlEvidence.ContentState.ACTIVE);
        assertThat(store.read(version.objectReference(), 10_000)).isEqualTo(body);
        assertThat(embedding.calls.get()).isEqualTo(1);

        advanceRefreshInterval();
        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        assertThat(urls.contentVersions("tenant", jobId)).hasSize(1);
        assertThat(embedding.calls.get()).isEqualTo(1);
        assertThat(urls.observations("tenant", jobId, 10)).hasSize(2);
    }

    @Test
    void notModifiedCreatesOnlyObservation() {
        var coordinator = coordinator(command -> new SecureUrlFetchApplicationApi.FetchResult(
                command.normalizedUrl(), 304, null, "UTF-8", "etag", "date", new byte[0], 0));

        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        assertThat(urls.contentVersions("tenant", jobId)).isEmpty();
        assertThat(chunks.findByDocumentId(documentId)).isEmpty();
        assertThat(embedding.calls.get()).isZero();
        assertThat(urls.observations("tenant", jobId, 10).getFirst().outcome())
                .isEqualTo(KnowledgeUrlEvidence.ObservationOutcome.NOT_MODIFIED);
    }

    @Test
    void reactivatesHistoricalContentWhenTheSourceChangesFromAToBToA() {
        byte[][] bodies = {
                "content-a".getBytes(StandardCharsets.UTF_8),
                "content-b".getBytes(StandardCharsets.UTF_8),
                "content-a".getBytes(StandardCharsets.UTF_8)
        };
        AtomicInteger fetches = new AtomicInteger();
        var coordinator = coordinator(command -> result(
                bodies[Math.min(fetches.getAndIncrement(), bodies.length - 1)], 200));

        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        advanceRefreshInterval();
        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        advanceRefreshInterval();
        assertThat(coordinator.runOnce("worker", 60)).isTrue();

        assertThat(urls.contentVersions("tenant", jobId))
                .extracting(KnowledgeUrlEvidence.ContentVersion::state)
                .containsExactly(
                        KnowledgeUrlEvidence.ContentState.ACTIVE,
                        KnowledgeUrlEvidence.ContentState.SUPERSEDED);
        assertThat(chunks.findByDocumentId(documentId))
                .extracting(com.spaceagent.platform.knowledge.domain.KnowledgeChunk::content)
                .containsExactly("content-a");
        assertThat(embedding.calls.get()).isEqualTo(3);
        assertThat(urls.observations("tenant", jobId, 10)).hasSize(3);
    }

    @Test
    void expiredLeaseCannotContinueIntoEmbeddingOrActivation() {
        byte[] body = "stale".getBytes(StandardCharsets.UTF_8);
        var stale = coordinator(command -> {
            time.set(time.get().plusSeconds(61));
            return result(body, 200);
        });

        assertThat(stale.runOnce("stale-worker", 60)).isTrue();
        assertThat(embedding.calls.get()).isZero();
        assertThat(urls.contentVersions("tenant", jobId)).isEmpty();
        assertThat(chunks.findByDocumentId(documentId)).isEmpty();

        var replacement = coordinator(command -> result(body, 200));
        assertThat(replacement.runOnce("replacement-worker", 60)).isTrue();
        assertThat(embedding.calls.get()).isEqualTo(1);
        assertThat(chunks.findByDocumentId(documentId)).hasSize(1);
    }

    @Test
    void activationAmbiguityPausesTheJobAndNeverBlindlyReclaimsIt() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        KnowledgeUrlActivationService failing = new KnowledgeUrlActivationService(
                urls, chunks, documents) {
            @Override
            public void activate(Command command) {
                throw new IllegalStateException("ambiguous commit");
            }
        };
        var coordinator = coordinator(command -> result(body, 200), failing);

        assertThat(coordinator.runOnce("worker", 60)).isTrue();
        assertThat(urls.observations("tenant", jobId, 10))
                .extracting(KnowledgeUrlEvidence.Observation::outcome)
                .contains(KnowledgeUrlEvidence.ObservationOutcome.UNKNOWN);
        assertThat(urls.findJob("tenant", "owner", jobId)).get()
                .extracting(KnowledgeUrlJob::state)
                .isEqualTo(KnowledgeUrlJob.State.PAUSED);
        time.set(time.get().plusSeconds(3_600));
        assertThat(coordinator.runOnce("worker-two", 60)).isFalse();
        assertThat(chunks.findByDocumentId(documentId)).isEmpty();
    }

    private void advanceRefreshInterval() {
        time.set(time.get().plusSeconds(301));
    }

    private KnowledgeUrlRefreshCoordinator coordinator(SecureUrlFetchApplicationApi fetch) {
        return coordinator(fetch, new KnowledgeUrlActivationService(urls, chunks, documents));
    }

    private KnowledgeUrlRefreshCoordinator coordinator(
            SecureUrlFetchApplicationApi fetch,
            KnowledgeUrlActivationService activation) {
        return new KnowledgeUrlRefreshCoordinator(
                urls, fetch, store, new KnowledgeUrlContentParser(new ObjectMapper()),
                new KnowledgeChunkingService(100, 10), embedding, activation,
                () -> new UUID(0, ids.incrementAndGet()).toString(), (tenant, user, write) -> {});
    }

    private SecureUrlFetchApplicationApi.FetchResult result(byte[] body, int status) {
        return new SecureUrlFetchApplicationApi.FetchResult(
                "https://docs.example.com/", status, "text/html;charset=UTF-8", "UTF-8",
                "etag", "date", body, 0);
    }

    private static class CountingEmbedding implements KnowledgeEmbeddingGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public EmbeddingBatch embed(List<String> inputs) {
            calls.incrementAndGet();
            return new DeterministicKnowledgeEmbeddingGateway().embed(inputs);
        }
    }
}
