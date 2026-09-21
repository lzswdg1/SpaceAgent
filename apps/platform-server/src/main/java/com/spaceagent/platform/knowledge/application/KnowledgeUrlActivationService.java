package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.domain.KnowledgeChunk;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class KnowledgeUrlActivationService {
    private final KnowledgeUrlRepository urls;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private KnowledgeIndexCatalogApplicationService indexCatalog;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureIndexCatalog(KnowledgeIndexCatalogApplicationService indexCatalog) { this.indexCatalog=indexCatalog; }

    public KnowledgeUrlActivationService(
            KnowledgeUrlRepository urls,
            KnowledgeChunkRepository chunks,
            KnowledgeDocumentRepository documents) {
        this.urls = urls;
        this.chunks = chunks;
        this.documents = documents;
    }

    @Transactional
    public void activate(Command command) {
        if(indexCatalog!=null && !indexCatalog.legacyDocumentVisible(command.documentId(),command.ownerId()))
            throw new IllegalStateException("Knowledge document scope is no longer accessible");
        if (documents.findById(command.documentId())
                .filter(value -> java.util.Objects.equals(value.ownerId(),command.ownerId())).isEmpty()) {
            throw new IllegalStateException("Knowledge document scope changed");
        }
        chunks.deleteByDocumentId(command.documentId());
        command.chunks().forEach(chunks::save);
        urls.activateContentVersion(
                        command.tenantId(), command.urlJobId(), command.contentVersionId(),
                        command.claimToken(), command.fencingToken(), command.leaseRevision(),
                        command.activatedAt())
                .orElseThrow(() -> new IllegalStateException(
                        "Knowledge URL content activation lost its lease fence"));
        documents.updateStatus(command.documentId(), KnowledgeDocumentStatus.READY, null);
    }

    public record Command(
            String tenantId,
            String ownerId,
            String urlJobId,
            String documentId,
            String contentVersionId,
            String claimToken,
            long fencingToken,
            long leaseRevision,
            List<KnowledgeChunk> chunks,
            Instant activatedAt) {
        public Command {
            chunks = List.copyOf(chunks);
            if (claimToken == null || claimToken.isBlank()
                    || fencingToken <= 0 || leaseRevision <= 0) {
                throw new IllegalArgumentException("Knowledge URL activation lease is invalid");
            }
        }
    }
}
