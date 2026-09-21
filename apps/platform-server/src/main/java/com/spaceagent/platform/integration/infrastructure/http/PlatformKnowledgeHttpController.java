package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.knowledge.api.AddKnowledgeChunkCommand;
import com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeChunkView;
import com.spaceagent.platform.knowledge.api.KnowledgeDocumentView;
import com.spaceagent.platform.knowledge.api.KnowledgeProcessingView;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalView;
import com.spaceagent.platform.knowledge.api.MarkKnowledgeDocumentStatusCommand;
import com.spaceagent.platform.knowledge.api.ProcessKnowledgeDocumentCommand;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Public knowledge document/chunk HTTP adapter.
 *
 * <p>File ingestion is not reimplemented here. This edge exposes the migrated
 * document/chunk ownership and status APIs required by the client; raw file parsing
 * remains a documented unique capability still to migrate.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformKnowledgeHttpController {

    private final KnowledgeApplicationApi knowledgeApi;

    public PlatformKnowledgeHttpController(KnowledgeApplicationApi knowledgeApi) {
        this.knowledgeApi = knowledgeApi;
    }

    @GetMapping("/knowledge/documents")
    public ApiResponse<List<DocumentResponse>> listDocuments(Authentication authentication) {
        return ApiResponse.ok(knowledgeApi.findDocumentsByOwner(PlatformHttpSupport.userId(authentication)).stream()
                .map(DocumentResponse::from)
                .toList());
    }

    @PostMapping("/knowledge/documents")
    public ApiResponse<DocumentResponse> createDocument(
            @Valid @RequestBody CreateDocumentRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        KnowledgeDocumentView document = knowledgeApi.createDocument(new CreateKnowledgeDocumentCommand(
                PlatformHttpSupport.userId(authentication),
                request.name(),
                request.contentType(),
                request.storageLocation()));
        return ApiResponse.ok(DocumentResponse.from(document));
    }

    @PostMapping("/knowledge/documents/upload-reference")
    public ApiResponse<DocumentResponse> createUploadReference(
            @Valid @RequestBody UploadReferenceRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        KnowledgeDocumentView document = knowledgeApi.createDocument(new CreateKnowledgeDocumentCommand(
                PlatformHttpSupport.userId(authentication),
                request.name(),
                request.contentType(),
                request.storageReference()));
        return ApiResponse.ok(DocumentResponse.from(document));
    }

    @GetMapping("/knowledge/documents/{documentId}")
    public ApiResponse<DocumentResponse> getDocument(
            @PathVariable String documentId,
            Authentication authentication) {
        return ApiResponse.ok(DocumentResponse.from(requireDocument(documentId, authentication)));
    }

    @PostMapping("/knowledge/documents/{documentId}/chunks")
    public ApiResponse<ChunkResponse> addChunk(
            @PathVariable String documentId,
            @Valid @RequestBody AddChunkRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireDocument(documentId, authentication);
        KnowledgeChunkView chunk = knowledgeApi.addChunk(new AddKnowledgeChunkCommand(
                documentId,
                request.sequence(),
                request.content(),
                request.embeddingReference()));
        return ApiResponse.ok(ChunkResponse.from(chunk));
    }

    @GetMapping("/knowledge/documents/{documentId}/chunks")
    public ApiResponse<List<ChunkResponse>> chunks(
            @PathVariable String documentId,
            Authentication authentication) {
        requireDocument(documentId, authentication);
        return ApiResponse.ok(knowledgeApi.findChunksByDocument(documentId).stream()
                .map(ChunkResponse::from)
                .toList());
    }

    @PostMapping("/knowledge/documents/{documentId}/status")
    public ApiResponse<DocumentResponse> status(
            @PathVariable String documentId,
            @Valid @RequestBody StatusRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireDocument(documentId, authentication);
        KnowledgeDocumentView updated = knowledgeApi.markDocumentStatus(new MarkKnowledgeDocumentStatusCommand(
                documentId,
                request.status(),
                request.errorReason()));
        return ApiResponse.ok(DocumentResponse.from(updated));
    }

    @PostMapping("/knowledge/documents/{documentId}/process")
    public ApiResponse<ProcessingResponse> process(
            @PathVariable String documentId,
            @Valid @RequestBody(required = false) ProcessRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireDocument(documentId, authentication);
        KnowledgeProcessingView processed = knowledgeApi.process(new ProcessKnowledgeDocumentCommand(
                documentId,
                PlatformHttpSupport.userId(authentication),
                request == null ? null : request.content()));
        return ApiResponse.ok(ProcessingResponse.from(processed));
    }

    @PostMapping("/knowledge/retrieve")
    public ApiResponse<KnowledgeRetrievalView> retrieve(
            @Valid @RequestBody RetrievalRequest request,
            Authentication authentication) {
        return ApiResponse.ok(knowledgeApi.retrieve(new KnowledgeRetrievalCommand(
                PlatformHttpSupport.userId(authentication),
                request.documentIds(),
                request.query(),
                request.topK())));
    }

    @DeleteMapping("/knowledge/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable String documentId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireDocument(documentId, authentication);
        knowledgeApi.deleteDocument(documentId);
        return ApiResponse.ok(null);
    }

    private KnowledgeDocumentView requireDocument(String documentId, Authentication authentication) {
        return knowledgeApi.findDocument(documentId)
                .filter(document -> PlatformHttpSupport.userId(authentication).equals(document.ownerId()))
                .orElseThrow(() -> new BusinessException("Knowledge document not found", HttpStatus.NOT_FOUND));
    }

    public record CreateDocumentRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 100) String contentType,
            @NotBlank @Size(max = 1000) String storageLocation) {
    }

    public record UploadReferenceRequest(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 100) String contentType,
            @NotBlank @Size(max = 1000) String storageReference) {
    }

    public record ProcessRequest(@Size(max = 1_000_000) String content) {
    }

    public record RetrievalRequest(
            @Size(max = 64) List<@NotBlank @Size(max = 64) String> documentIds,
            @NotBlank @Size(max = 8_000) String query,
            int topK) {
    }

    public record AddChunkRequest(
            @Min(0) @Max(511) int sequence,
            @NotBlank @Size(max = 20_000) String content,
            @Size(max = 1_000) String embeddingReference) {
    }

    public record StatusRequest(
            @NotNull KnowledgeDocumentStatus status,
            @Size(max = 500) String errorReason) {
    }

    public record DocumentResponse(
            String id,
            String ownerId,
            String name,
            String contentType,
            String storageLocation,
            String status,
            String errorReason,
            Instant createdAt,
            Instant updatedAt) {

        static DocumentResponse from(KnowledgeDocumentView document) {
            return new DocumentResponse(
                    document.id(),
                    document.ownerId(),
                    document.name(),
                    document.contentType(),
                    document.storageLocation(),
                    document.status().name(),
                    null,
                    document.createdAt(),
                    document.updatedAt());
        }
    }

    public record ChunkResponse(
            String id,
            String documentId,
            int sequence,
            String content,
            String embeddingReference,
            String contentHash,
            String embeddingModel,
            int embeddingDimensions,
            Instant createdAt) {

        static ChunkResponse from(KnowledgeChunkView chunk) {
            return new ChunkResponse(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.sequence(),
                    chunk.content(),
                    chunk.embeddingReference(),
                    chunk.contentHash(),
                    chunk.embeddingModel(),
                    chunk.embeddingDimensions(),
                    chunk.createdAt());
        }
    }

    public record ProcessingResponse(
            DocumentResponse document,
            List<ChunkResponse> chunks) {

        static ProcessingResponse from(KnowledgeProcessingView processing) {
            return new ProcessingResponse(
                    DocumentResponse.from(processing.document()),
                    processing.chunks().stream().map(ChunkResponse::from).toList());
        }
    }

}
