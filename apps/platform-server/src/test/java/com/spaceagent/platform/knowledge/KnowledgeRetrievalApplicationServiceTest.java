package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.application.KnowledgeApplicationService;
import com.spaceagent.platform.knowledge.application.KnowledgeChunkingService;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentParser;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalApplicationServiceTest {
    @Test
    void delegatesBoundedTopKScoringToTheRepository() {
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingGateway embeddings = mock(KnowledgeEmbeddingGateway.class);
        KnowledgeDocument ready = new KnowledgeDocument(
                "document", "owner", "Knowledge", "text/plain", "managed:document",
                KnowledgeDocumentStatus.READY, Instant.EPOCH, Instant.EPOCH);
        when(documents.findByIds(List.of("document"))).thenReturn(List.of(ready));
        when(embeddings.embed(List.of("query"))).thenReturn(
                new KnowledgeEmbeddingGateway.EmbeddingBatch("model", List.of(List.of(1.0, 0.0))));
        when(chunks.findNearestByDocumentIds(
                List.of("document"), List.of(1.0, 0.0), 3)).thenReturn(List.of(
                new KnowledgeChunkRepository.SimilarityMatch(
                        "document", "chunk", 0, "answer", 0.95, "model")));
        var service = new KnowledgeApplicationService(
                documents, chunks, () -> "id", () -> Instant.EPOCH,
                mock(KnowledgeDocumentParser.class), embeddings,
                new KnowledgeChunkingService(100, 10), user -> true);

        var result = service.retrieve(new KnowledgeRetrievalCommand(
                "owner", List.of("document"), "query", 3));

        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.chunkId()).isEqualTo("chunk");
            assertThat(match.score()).isEqualTo(0.95);
        });
        verify(chunks, never()).findByDocumentIds(List.of("document"));
    }

    @Test
    void implicitOwnerScopeLoadsAtMostSixtyFourReadyDocuments() {
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeEmbeddingGateway embeddings = mock(KnowledgeEmbeddingGateway.class);
        when(documents.findReadyByOwnerId("owner", 64)).thenReturn(List.of());
        var service = new KnowledgeApplicationService(
                documents, chunks, () -> "id", () -> Instant.EPOCH,
                mock(KnowledgeDocumentParser.class), embeddings,
                new KnowledgeChunkingService(100, 10), user -> true);

        assertThat(service.retrieve(new KnowledgeRetrievalCommand(
                "owner", List.of(), "query", 3)).matches()).isEmpty();

        verify(documents).findReadyByOwnerId("owner", 64);
        verify(embeddings, never()).embed(List.of("query"));
    }
}
