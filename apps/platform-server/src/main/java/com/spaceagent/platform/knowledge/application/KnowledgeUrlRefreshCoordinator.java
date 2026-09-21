package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.knowledge.domain.KnowledgeChunk;
import com.spaceagent.platform.knowledge.domain.KnowledgeEmbeddingGateway;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlContentStore;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlEvidence;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlRepository;
import com.spaceagent.platform.tooling.api.SecureUrlFetchApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class KnowledgeUrlRefreshCoordinator {
    private static final int MAX_CHUNKS = 512;
    private static final int MIN_LEASE_SECONDS = 60;
    private static final int MAX_LEASE_SECONDS = 300;
    private static final long RETRY_DELAY_SECONDS = 300;

    private final KnowledgeUrlRepository urls;
    private final SecureUrlFetchApplicationApi fetch;
    private final KnowledgeUrlContentStore store;
    private final KnowledgeUrlContentParser parser;
    private final KnowledgeChunkingService chunking;
    private final KnowledgeEmbeddingGateway embedding;
    private final KnowledgeUrlActivationService activation;
    private final IdGenerator ids;
    private KnowledgeIndexCatalogApplicationService indexCatalog;
    private final com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureIndexCatalog(KnowledgeIndexCatalogApplicationService indexCatalog) { this.indexCatalog=indexCatalog; }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeUrlRefreshCoordinator(
            KnowledgeUrlRepository urls,
            SecureUrlFetchApplicationApi fetch,
            KnowledgeUrlContentStore store,
            KnowledgeUrlContentParser parser,
            KnowledgeChunkingService chunking,
            KnowledgeEmbeddingGateway embedding,
            KnowledgeUrlActivationService activation,
            IdGenerator ids,
            com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization) {
        this.urls = urls;
        this.fetch = fetch;
        this.store = store;
        this.parser = parser;
        this.chunking = chunking;
        this.embedding = embedding;
        this.activation = activation;
        this.ids = ids;
        this.actorAuthorization = java.util.Objects.requireNonNull(actorAuthorization);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean runOnce(String worker, int requestedLeaseSeconds) {
        int leaseSeconds = Math.max(
                MIN_LEASE_SECONDS, Math.min(requestedLeaseSeconds, MAX_LEASE_SECONDS));
        var lease = urls.claimNext(worker, ids.nextId(), leaseSeconds).orElse(null);
        if (lease == null) return false;
        KnowledgeUrlJob job = lease.job();
        boolean activationStarted = false;
        try {
            actorAuthorization.requireActiveActor(job.tenantId(),job.ownerId(),true);
            if(indexCatalog!=null && !indexCatalog.legacyDocumentVisible(job.knowledgeDocumentId(),job.ownerId()))
                throw new BusinessException("Knowledge document is no longer accessible", HttpStatus.CONFLICT,
                        "KNOWLEDGE_DOCUMENT_NOT_ACCESSIBLE");
            var prior = urls.observations(job.tenantId(), job.id(), 1)
                    .stream().findFirst().orElse(null);
            var result = fetch.fetch(new SecureUrlFetchApplicationApi.FetchCommand(
                    job.normalizedUrl(), prior == null ? null : prior.etag(),
                    prior == null ? null : prior.lastModified(),
                    job.refreshPolicy().maximumRedirects(),
                    job.refreshPolicy().maximumBytes()));
            renew(lease, leaseSeconds);

            Instant observedAt = urls.currentTime();
            String requestHash = sha256(job.normalizedUrl().getBytes(StandardCharsets.UTF_8));
            String finalHash = sha256(result.finalUrl().getBytes(StandardCharsets.UTF_8));
            if (result.status() == 304) {
                urls.appendObservation(observation(
                        job, requestHash, result.finalUrl(), finalHash, result, null,
                        KnowledgeUrlEvidence.ObservationOutcome.NOT_MODIFIED, null, observedAt));
                release(lease, next(job, observedAt));
                return true;
            }

            String contentHash = sha256(result.body());
            var existingVersions = urls.contentVersions(job.tenantId(), job.id());
            var matching = existingVersions.stream()
                    .filter(value -> value.contentSha256().equals(contentHash))
                    .findFirst().orElse(null);
            var observed = observation(
                    job, requestHash, result.finalUrl(), finalHash, result, contentHash,
                    KnowledgeUrlEvidence.ObservationOutcome.FETCHED, null, observedAt);
            if (matching != null && matching.state() == KnowledgeUrlEvidence.ContentState.ACTIVE) {
                urls.appendObservation(observed);
                release(lease, next(job, observedAt));
                return true;
            }
            if (matching != null && matching.state() == KnowledgeUrlEvidence.ContentState.REJECTED) {
                throw new BusinessException(
                        "Rejected Knowledge URL content cannot be reactivated automatically",
                        HttpStatus.CONFLICT,
                        "KNOWLEDGE_URL_CONTENT_REJECTED");
            }

            String objectReference = matching == null
                    ? store.put(job.id(), contentHash, result.body())
                    : matching.objectReference();
            renew(lease, leaseSeconds);
            String parsed = parser.parse(result.body(), result.mediaType(), result.charset());
            List<String> texts = chunking.chunk(parsed);
            if (texts.isEmpty() || texts.size() > MAX_CHUNKS) {
                throw new BusinessException(
                        "Knowledge URL chunk bounds invalid",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "KNOWLEDGE_URL_CHUNK_BOUNDS");
            }
            renew(lease, leaseSeconds);
            actorAuthorization.requireActiveActor(job.tenantId(),job.ownerId(),true);
            var vectors = embedding.embed(texts);
            if (vectors.vectors().size() != texts.size()) {
                throw new BusinessException(
                        "Knowledge URL embedding count mismatch",
                        HttpStatus.BAD_GATEWAY,
                        "KNOWLEDGE_URL_EMBEDDING_COUNT_MISMATCH");
            }
            renew(lease, leaseSeconds);

            Instant activationTime = urls.currentTime();
            urls.appendObservation(observed);
            String versionId;
            if (matching == null) {
                int versionNumber = existingVersions.stream()
                        .mapToInt(KnowledgeUrlEvidence.ContentVersion::version)
                        .max().orElse(0) + 1;
                versionId = ids.nextId();
                urls.insertContentVersion(new KnowledgeUrlEvidence.ContentVersion(
                        versionId, job.id(), job.knowledgeDocumentId(), job.tenantId(),
                        versionNumber, contentHash, objectReference, result.mediaType(),
                        result.charset(), result.body().length,
                        KnowledgeUrlEvidence.ContentState.STAGED, observed.id(),
                        activationTime, null));
            } else {
                versionId = matching.id();
            }
            List<KnowledgeChunk> chunks = java.util.stream.IntStream.range(0, texts.size())
                    .mapToObj(index -> chunk(
                            job.knowledgeDocumentId(), index, texts.get(index), vectors.model(),
                            vectors.vectors().get(index), activationTime))
                    .toList();
            activationStarted = true;
            activation.activate(new KnowledgeUrlActivationService.Command(
                    job.tenantId(), job.ownerId(), job.id(), job.knowledgeDocumentId(),
                    versionId, lease.claimToken(), lease.fencingToken(), lease.revision(),
                    chunks, activationTime));
            release(lease, next(job, urls.currentTime()));
            return true;
        } catch (BusinessException error) {
            if (activationStarted) {
                pauseUnknown(lease, job, "KNOWLEDGE_URL_ACTIVATION_UNKNOWN");
            } else {
                retry(lease, job, error.getCode(),
                        KnowledgeUrlEvidence.ObservationOutcome.REJECTED, leaseSeconds);
            }
            return true;
        } catch (RuntimeException error) {
            if (activationStarted) {
                pauseUnknown(lease, job, "KNOWLEDGE_URL_ACTIVATION_UNKNOWN");
            } else {
                retry(lease, job, "KNOWLEDGE_URL_REFRESH_UNKNOWN",
                        KnowledgeUrlEvidence.ObservationOutcome.UNKNOWN, leaseSeconds);
            }
            return true;
        }
    }

    private void renew(KnowledgeUrlEvidence.RefreshLease lease, int leaseSeconds) {
        if (!urls.renewLease(
                lease.job().id(), lease.claimToken(), lease.fencingToken(),
                lease.revision(), leaseSeconds)) {
            throw new IllegalStateException("Knowledge URL lease renewal lost its fence");
        }
    }

    private void release(KnowledgeUrlEvidence.RefreshLease lease, Instant nextRefreshAt) {
        if (!urls.releaseLease(
                lease.job().id(), lease.claimToken(), lease.fencingToken(),
                lease.revision(), nextRefreshAt)) {
            throw new IllegalStateException("Knowledge URL lease release conflict");
        }
    }

    private void retry(
            KnowledgeUrlEvidence.RefreshLease lease,
            KnowledgeUrlJob job,
            String code,
            KnowledgeUrlEvidence.ObservationOutcome outcome,
            int leaseSeconds) {
        if (!urls.renewLease(
                job.id(), lease.claimToken(), lease.fencingToken(),
                lease.revision(), leaseSeconds)) {
            return;
        }
        safeObservation(job, code, outcome);
        release(lease, urls.currentTime().plusSeconds(RETRY_DELAY_SECONDS));
    }

    private void pauseUnknown(
            KnowledgeUrlEvidence.RefreshLease lease,
            KnowledgeUrlJob job,
            String code) {
        if (urls.markLeaseUnknown(
                job.id(), lease.claimToken(), lease.fencingToken(), lease.revision())) {
            safeObservation(job, code, KnowledgeUrlEvidence.ObservationOutcome.UNKNOWN);
        }
    }

    private static Instant next(KnowledgeUrlJob job, Instant now) {
        return now.plusSeconds(job.refreshPolicy().mode() == KnowledgeUrlJob.RefreshMode.PERIODIC
                ? job.refreshPolicy().intervalSeconds() : 315_360_000L);
    }

    private void safeObservation(
            KnowledgeUrlJob job,
            String code,
            KnowledgeUrlEvidence.ObservationOutcome outcome) {
        try {
            Instant now = urls.currentTime();
            String hash = sha256(job.normalizedUrl().getBytes(StandardCharsets.UTF_8));
            urls.appendObservation(new KnowledgeUrlEvidence.Observation(
                    ids.nextId(), job.id(), job.tenantId(), hash, job.normalizedUrl(), hash,
                    0, null, null, null, outcome, code, now));
        } catch (RuntimeException ignored) {
            // The lease state remains authoritative if secondary evidence cannot be written.
        }
    }

    private KnowledgeUrlEvidence.Observation observation(
            KnowledgeUrlJob job,
            String requestHash,
            String finalUrl,
            String finalHash,
            SecureUrlFetchApplicationApi.FetchResult result,
            String contentHash,
            KnowledgeUrlEvidence.ObservationOutcome outcome,
            String code,
            Instant now) {
        return new KnowledgeUrlEvidence.Observation(
                ids.nextId(), job.id(), job.tenantId(), requestHash, finalUrl, finalHash,
                result.status(), result.etag(), result.lastModified(), contentHash,
                outcome, code, now);
    }

    private KnowledgeChunk chunk(
            String documentId,
            int sequence,
            String text,
            String model,
            List<Double> vector,
            Instant now) {
        String hash = rawSha256(text.getBytes(StandardCharsets.UTF_8));
        return new KnowledgeChunk(
                ids.nextId(), documentId, sequence, text,
                "embedding:" + model + ":" + hash, hash, model, vector.size(), vector, now);
    }

    private static String sha256(byte[] value) {
        return "sha256:" + rawSha256(value);
    }

    private static String rawSha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
