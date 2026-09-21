package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.api.AddKnowledgeChunkCommand;
import com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeChunkView;
import com.spaceagent.platform.knowledge.api.KnowledgeDocumentView;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.knowledge.api.KnowledgeProcessingView;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalMatchView;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalView;
import com.spaceagent.platform.knowledge.api.MarkKnowledgeDocumentStatusCommand;
import com.spaceagent.platform.knowledge.api.ProcessKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunk;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentParser;
import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Function;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * In-module application coordinator for knowledge document/chunk ownership and
 * access. Retrieval/embedding orchestration remains outside this first M4 slice.
 */
@Service
public class KnowledgeApplicationService implements KnowledgeApplicationApi, KnowledgeOwnershipPort {
    private static final int MAX_PARSED_CHARACTERS = 1_000_000;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 512;

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final KnowledgeDocumentParser documentParser;
    private final KnowledgeEmbeddingGateway embeddingGateway;
    private final KnowledgeChunkingService chunkingService;
    private KnowledgeIndexCatalogApplicationService indexCatalog;
    @org.springframework.beans.factory.annotation.Value("${platform.knowledge.legacy-mode:compatibility}")
    private String legacyMode="compatibility";
    private final com.spaceagent.platform.identity.api.IdentityActorStatusPort actorStatus;

    @Autowired
    public void configureIndexCatalog(KnowledgeIndexCatalogApplicationService indexCatalog) {
        this.indexCatalog = indexCatalog;
    }

    @Autowired
    public KnowledgeApplicationService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            KnowledgeDocumentParser documentParser,
            KnowledgeEmbeddingGateway embeddingGateway,
            KnowledgeChunkingService chunkingService,
            com.spaceagent.platform.identity.api.IdentityActorStatusPort actorStatus) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.documentParser = documentParser;
        this.embeddingGateway = embeddingGateway;
        this.chunkingService = chunkingService;
        this.actorStatus = java.util.Objects.requireNonNull(actorStatus);
    }

    public KnowledgeApplicationService(KnowledgeDocumentRepository d, KnowledgeChunkRepository c,
            IdGenerator ids, TimeProvider time, KnowledgeDocumentParser parser, KnowledgeEmbeddingGateway embedding,
            KnowledgeChunkingService chunking) {
        this(d,c,ids,time,parser,embedding,chunking,user -> false);
    }

    public KnowledgeApplicationService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this(
                documentRepository,
                chunkRepository,
                idGenerator,
                timeProvider,
                (document, suppliedContent) -> suppliedContent == null ? "" : suppliedContent,
                inputs -> new KnowledgeEmbeddingGateway.EmbeddingBatch(
                        "unconfigured", inputs.stream().map(ignored -> List.<Double>of()).toList()),
                new KnowledgeChunkingService(800, 100));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public KnowledgeDocumentView createDocument(CreateKnowledgeDocumentCommand command) {
        Instant now = timeProvider.now();
        KnowledgeDocument document = new KnowledgeDocument(
                idGenerator.nextId(),
                command.ownerId(),
                command.name(),
                command.contentType(),
                command.storageLocation(),
                KnowledgeDocumentStatus.UPLOADED,
                now,
                now);
        documentRepository.save(document);
        if (indexCatalog != null) indexCatalog.registerLegacy(document);
        return toDocumentView(document);
    }

    @Override
    public Optional<KnowledgeDocumentView> findDocument(String documentId) {
        return documentRepository.findById(documentId)
                .filter(document -> legacyVisible(document, document.ownerId()))
                .map(KnowledgeApplicationService::toDocumentView);
    }

    @Override
    public KnowledgeChunkView addChunk(AddKnowledgeChunkCommand command) {
        requireLegacyEnabled();
        if (documentRepository.findById(command.documentId()).isEmpty()) {
            throw new BusinessException(
                    "Knowledge document not found: " + command.documentId(),
                    HttpStatus.NOT_FOUND);
        }

        KnowledgeChunk chunk = new KnowledgeChunk(
                idGenerator.nextId(),
                command.documentId(),
                command.sequence(),
                command.content(),
                command.embeddingReference(),
                timeProvider.now());
        chunkRepository.save(chunk);
        return toChunkView(chunk);
    }

    @Override
    public List<KnowledgeChunkView> findChunksByDocument(String documentId) {
        return chunkRepository.findByDocumentId(documentId).stream()
                .map(KnowledgeApplicationService::toChunkView)
                .toList();
    }

    @Override
    public List<KnowledgeDocumentView> findDocumentsByOwner(String ownerId) {
        return documentRepository.findByOwnerId(ownerId).stream()
                .filter(document -> legacyVisible(document, ownerId))
                .map(KnowledgeApplicationService::toDocumentView)
                .toList();
    }

    @Override
    public KnowledgeDocumentView markDocumentStatus(MarkKnowledgeDocumentStatusCommand command) {
        if (documentRepository.findById(command.documentId()).isEmpty()) {
            throw new BusinessException(
                    "Knowledge document not found: " + command.documentId(),
                    HttpStatus.NOT_FOUND);
        }
        documentRepository.updateStatus(command.documentId(), command.status(), command.errorReason());
        return documentRepository.findById(command.documentId())
                .map(KnowledgeApplicationService::toDocumentView)
                .orElseThrow();
    }

    @Override
    public KnowledgeProcessingView process(ProcessKnowledgeDocumentCommand command) {
        requireLegacyEnabled();
        KnowledgeDocument document = requireOwnedDocument(command.documentId(), command.ownerId());
        documentRepository.updateStatus(document.id(), KnowledgeDocumentStatus.PROCESSING, null);
        try {
            String parsed = documentParser.parse(document, command.suppliedContent());
            if (parsed.length() > MAX_PARSED_CHARACTERS) {
                throw new BusinessException(
                        "Knowledge document exceeds processing limit",
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "KNOWLEDGE_DOCUMENT_TOO_LARGE");
            }
            List<String> contents = chunkingService.chunk(parsed);
            if (contents.isEmpty()) {
                throw new BusinessException(
                        "Knowledge document produced no chunks",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "KNOWLEDGE_DOCUMENT_EMPTY");
            }
            if (contents.size() > MAX_CHUNKS_PER_DOCUMENT) {
                throw new BusinessException(
                        "Knowledge document produced too many chunks",
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "KNOWLEDGE_CHUNK_LIMIT_EXCEEDED");
            }
            requireActiveOwner(command.ownerId());
            KnowledgeEmbeddingGateway.EmbeddingBatch embeddings = embeddingGateway.embed(contents);
            if (embeddings.vectors().size() != contents.size()) {
                throw new BusinessException(
                        "Embedding result count does not match chunk count",
                        HttpStatus.BAD_GATEWAY,
                        "KNOWLEDGE_EMBEDDING_COUNT_MISMATCH");
            }
            chunkRepository.deleteByDocumentId(document.id());
            List<KnowledgeChunkView> chunks = java.util.stream.IntStream.range(0, contents.size())
                    .mapToObj(index -> saveProcessedChunk(
                            document.id(), index, contents.get(index), embeddings.model(),
                            embeddings.vectors().get(index)))
                    .toList();
            documentRepository.updateStatus(document.id(), KnowledgeDocumentStatus.READY, null);
            KnowledgeDocumentView ready = findDocument(document.id()).orElseThrow();
            return new KnowledgeProcessingView(ready, chunks);
        } catch (RuntimeException exception) {
            documentRepository.updateStatus(
                    document.id(),
                    KnowledgeDocumentStatus.FAILED,
                    abbreviate(exception.getMessage(), 500));
            throw exception;
        }
    }

    @Override
    public KnowledgeRetrievalView retrieve(KnowledgeRetrievalCommand command) {
        requireLegacyEnabled();
        List<KnowledgeDocument> documents = command.documentIds().isEmpty()
                ? documentRepository.findReadyByOwnerId(command.ownerId(), 64).stream()
                    .filter(document -> legacyVisible(document, command.ownerId())).toList()
                : ownedDocuments(command.documentIds(), command.ownerId()).stream()
                .filter(document -> document.status() == KnowledgeDocumentStatus.READY)
                .toList();
        if (documents.isEmpty()) {
            return new KnowledgeRetrievalView(command.query(), List.of());
        }
        requireActiveOwner(command.ownerId());
        List<Double> queryVector = embeddingGateway.embed(List.of(command.query())).vectors().stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Embedding provider returned no query vector",
                        HttpStatus.BAD_GATEWAY));
        List<KnowledgeRetrievalMatchView> matches = chunkRepository.findNearestByDocumentIds(
                        documents.stream().map(KnowledgeDocument::id).toList(),
                        queryVector, command.topK()).stream()
                .map(match -> new KnowledgeRetrievalMatchView(
                        match.documentId(), match.chunkId(), match.sequence(), match.content(),
                        match.score(), match.embeddingModel()))
                .toList();
        return new KnowledgeRetrievalView(command.query(), matches);
    }

    @Override
    public void deleteDocument(String documentId) {
        if (documentRepository.findById(documentId).isEmpty()) {
            throw new BusinessException(
                    "Knowledge document not found: " + documentId,
                    HttpStatus.NOT_FOUND);
        }
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(documentId);
    }

    @Override
    public boolean canAccess(String documentId, String principalId) {
        return documentRepository.findById(documentId)
                .filter(document -> legacyVisible(document, principalId))
                .isPresent();
    }

    private KnowledgeChunkView saveProcessedChunk(
            String documentId,
            int sequence,
            String content,
            String embeddingModel,
            List<Double> embedding) {
        String contentHash = sha256(content);
        KnowledgeChunk chunk = new KnowledgeChunk(
                idGenerator.nextId(),
                documentId,
                sequence,
                content,
                "embedding:" + embeddingModel + ":" + contentHash,
                contentHash,
                embeddingModel,
                embedding.size(),
                embedding,
                timeProvider.now());
        chunkRepository.save(chunk);
        return toChunkView(chunk);
    }

    private KnowledgeDocument requireOwnedDocument(String documentId, String ownerId) {
        return documentRepository.findById(documentId)
                .filter(document -> legacyVisible(document, ownerId))
                .orElseThrow(() -> new BusinessException(
                        "Knowledge document not found: " + documentId,
                        HttpStatus.NOT_FOUND));
    }

    private List<KnowledgeDocument> ownedDocuments(List<String> ids, String ownerId) {
        Map<String, KnowledgeDocument> documents = documentRepository.findByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeDocument::id, Function.identity()));
        return ids.stream().map(id -> Optional.ofNullable(documents.get(id))
                        .filter(document -> legacyVisible(document, ownerId))
                        .orElseThrow(() -> new BusinessException(
                                "Knowledge document not found: " + id,
                                HttpStatus.NOT_FOUND)))
                .toList();
    }

    private boolean legacyVisible(KnowledgeDocument document, String ownerId) {
        return ownerId!=null && java.util.Objects.equals(document.ownerId(),ownerId) && (indexCatalog == null || indexCatalog.legacyVisible(document, ownerId));
    }

    private void requireActiveOwner(String userId) {
        if(!actorStatus.isUserActive(userId))throw new BusinessException("Owner is no longer authorized for Knowledge execution",
                HttpStatus.FORBIDDEN,"EXECUTION_ACTOR_UNAVAILABLE");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Knowledge chunk", exception);
        }
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null || value.isBlank() ? "Knowledge processing failed" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static KnowledgeDocumentView toDocumentView(KnowledgeDocument document) {
        return new KnowledgeDocumentView(
                document.id(),
                document.ownerId(),
                document.name(),
                document.contentType(),
                document.storageLocation(),
                document.status(),
                document.createdAt(),
                document.updatedAt());
    }

    private void requireLegacyEnabled(){
        if(!"compatibility".equals(legacyMode))throw new BusinessException("Legacy vectors require explicit copy/rebuild and Knowledge collection bindings",HttpStatus.CONFLICT,"KNOWLEDGE_LEGACY_MIGRATION_REQUIRED");
    }
    private static KnowledgeChunkView toChunkView(KnowledgeChunk chunk) {
        return new KnowledgeChunkView(
                chunk.id(),
                chunk.documentId(),
                chunk.sequence(),
                chunk.content(),
                chunk.embeddingReference(),
                chunk.contentHash(),
                chunk.embeddingModel(),
                chunk.embeddingDimensions(),
                chunk.embedding(),
                chunk.createdAt());
    }
}
