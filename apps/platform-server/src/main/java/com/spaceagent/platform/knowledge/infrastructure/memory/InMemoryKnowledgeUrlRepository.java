package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeUrlEvidence;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlJob;
import com.spaceagent.platform.knowledge.domain.KnowledgeUrlRepository;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeUrlRepository implements KnowledgeUrlRepository {
    private final Map<String, KnowledgeUrlJob> jobs = new HashMap<>();
    private final Map<String, KnowledgeUrlEvidence.RefreshLease> leases = new HashMap<>();
    private final Map<String, Long> fencingTokens = new HashMap<>();
    private final Map<String, KnowledgeUrlEvidence.Observation> observations = new HashMap<>();
    private final Map<String, KnowledgeUrlEvidence.ContentVersion> versions = new HashMap<>();
    private final TimeProvider time;

    public InMemoryKnowledgeUrlRepository(TimeProvider time) {
        this.time = time;
    }

    @Override
    public Instant currentTime() {
        return time.now();
    }

    @Override
    public synchronized void insertJob(KnowledgeUrlJob job) {
        if (jobs.putIfAbsent(job.id(), job) != null) {
            throw new IllegalStateException("URL job exists");
        }
    }

    @Override
    public synchronized Optional<KnowledgeUrlJob> findJob(
            String tenantId, String ownerId, String jobId) {
        return Optional.ofNullable(jobs.get(jobId))
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.ownerId().equals(ownerId));
    }

    @Override
    public synchronized Optional<KnowledgeUrlJob> findJob(
            String tenantId, String ownerId, String documentId, String normalizedUrl) {
        return jobs.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.ownerId().equals(ownerId)
                        && value.knowledgeDocumentId().equals(documentId)
                        && value.normalizedUrl().equals(normalizedUrl))
                .findFirst();
    }

    @Override
    public synchronized List<KnowledgeUrlJob> findJobsByDocument(
            String tenantId, String ownerId, String documentId, int offset, int limit) {
        return jobs.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.ownerId().equals(ownerId)
                        && value.knowledgeDocumentId().equals(documentId))
                .sorted(Comparator.comparing(KnowledgeUrlJob::updatedAt).reversed()
                        .thenComparing(KnowledgeUrlJob::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long countJobsByDocument(
            String tenantId, String ownerId, String documentId) {
        return jobs.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.ownerId().equals(ownerId)
                        && value.knowledgeDocumentId().equals(documentId))
                .count();
    }

    @Override
    public synchronized Optional<KnowledgeUrlJob> updateJob(
            KnowledgeUrlJob value, long expectedRevision) {
        var current = jobs.get(value.id());
        if (current == null || current.revision() != expectedRevision
                || value.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        jobs.put(value.id(), value);
        return Optional.of(value);
    }

    @Override
    public synchronized Optional<KnowledgeUrlEvidence.RefreshLease> claimNext(
            String workerId, String claimToken, int leaseSeconds) {
        Instant now = currentTime();
        return jobs.values().stream()
                .filter(job -> job.state() == KnowledgeUrlJob.State.ACTIVE
                        && !job.nextRefreshAt().isAfter(now))
                .filter(job -> {
                    var lease = leases.get(job.id());
                    return lease == null || !lease.leaseUntil().isAfter(now);
                })
                .min(Comparator.comparing(KnowledgeUrlJob::nextRefreshAt)
                        .thenComparing(KnowledgeUrlJob::id))
                .map(job -> {
                    long fencingToken = fencingTokens.getOrDefault(job.id(), 0L) + 1;
                    fencingTokens.put(job.id(), fencingToken);
                    var claimed = copy(job, job.revision() + 1, job.nextRefreshAt(), now);
                    var lease = new KnowledgeUrlEvidence.RefreshLease(
                            claimed, claimToken, workerId, now.plusSeconds(leaseSeconds),
                            fencingToken, claimed.revision(), now);
                    leases.put(job.id(), lease);
                    jobs.put(job.id(), claimed);
                    return lease;
                });
    }

    @Override
    public synchronized boolean renewLease(
            String jobId, String claimToken, long fencingToken,
            long expectedRevision, int leaseSeconds) {
        Instant now = currentTime();
        var lease = leases.get(jobId);
        var job = jobs.get(jobId);
        if (!matches(job, lease, claimToken, fencingToken, expectedRevision)
                || !lease.leaseUntil().isAfter(now)) {
            return false;
        }
        leases.put(jobId, new KnowledgeUrlEvidence.RefreshLease(
                job, claimToken, lease.claimOwner(), now.plusSeconds(leaseSeconds),
                fencingToken, expectedRevision, now));
        return true;
    }

    @Override
    public synchronized boolean markLeaseUnknown(
            String jobId, String claimToken, long fencingToken, long expectedRevision) {
        var lease = leases.get(jobId);
        var job = jobs.get(jobId);
        if (!matches(job, lease, claimToken, fencingToken, expectedRevision)) {
            return false;
        }
        Instant now = currentTime();
        leases.remove(jobId);
        jobs.put(jobId, new KnowledgeUrlJob(
                job.id(), job.tenantId(), job.ownerId(), job.knowledgeDocumentId(),
                job.normalizedUrl(), job.origin(), job.refreshPolicy(),
                KnowledgeUrlJob.State.PAUSED, job.revision() + 1, job.nextRefreshAt(),
                job.createdAt(), now, null));
        return true;
    }

    @Override
    public synchronized boolean releaseLease(
            String jobId, String claimToken, long fencingToken,
            long expectedRevision, Instant nextRefreshAt) {
        var lease = leases.get(jobId);
        var job = jobs.get(jobId);
        if (!matches(job, lease, claimToken, fencingToken, expectedRevision)) {
            return false;
        }
        leases.remove(jobId);
        jobs.put(jobId, copy(job, job.revision() + 1, nextRefreshAt, currentTime()));
        return true;
    }

    @Override
    public synchronized void appendObservation(KnowledgeUrlEvidence.Observation value) {
        if (!jobs.containsKey(value.urlJobId())
                || observations.putIfAbsent(value.id(), value) != null) {
            throw new IllegalStateException("Observation scope invalid");
        }
    }

    @Override
    public synchronized List<KnowledgeUrlEvidence.Observation> observations(
            String tenantId, String jobId, int limit) {
        return observations.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.urlJobId().equals(jobId))
                .sorted(Comparator.comparing(KnowledgeUrlEvidence.Observation::observedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void insertContentVersion(KnowledgeUrlEvidence.ContentVersion value) {
        if (versions.putIfAbsent(value.id(), value) != null) {
            throw new IllegalStateException("Content version exists");
        }
    }

    @Override
    public synchronized List<KnowledgeUrlEvidence.ContentVersion> contentVersions(
            String tenantId, String jobId) {
        return versions.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.urlJobId().equals(jobId))
                .sorted(Comparator.comparingInt(KnowledgeUrlEvidence.ContentVersion::version))
                .toList();
    }

    @Override
    public synchronized Optional<KnowledgeUrlEvidence.ContentVersion> activateContentVersion(
            String tenantId, String jobId, String versionId, String claimToken,
            long fencingToken, long expectedLeaseRevision, Instant at) {
        var lease = leases.get(jobId);
        var job = jobs.get(jobId);
        if (!matches(job, lease, claimToken, fencingToken, expectedLeaseRevision)
                || !lease.leaseUntil().isAfter(currentTime())) {
            return Optional.empty();
        }
        var target = versions.get(versionId);
        if (target == null || !target.tenantId().equals(tenantId)
                || !target.urlJobId().equals(jobId)
                || !List.of(
                        KnowledgeUrlEvidence.ContentState.STAGED,
                        KnowledgeUrlEvidence.ContentState.SUPERSEDED).contains(target.state())) {
            return Optional.empty();
        }
        versions.replaceAll((key, value) -> value.urlJobId().equals(jobId)
                && value.state() == KnowledgeUrlEvidence.ContentState.ACTIVE
                ? content(value, KnowledgeUrlEvidence.ContentState.SUPERSEDED, value.activatedAt())
                : value);
        var active = content(target, KnowledgeUrlEvidence.ContentState.ACTIVE, at);
        versions.put(versionId, active);
        return Optional.of(active);
    }

    private static boolean matches(
            KnowledgeUrlJob job,
            KnowledgeUrlEvidence.RefreshLease lease,
            String claimToken,
            long fencingToken,
            long expectedRevision) {
        return lease != null && job != null && job.state() == KnowledgeUrlJob.State.ACTIVE
                && lease.revision() == expectedRevision
                && lease.claimToken().equals(claimToken)
                && lease.fencingToken() == fencingToken;
    }

    private static KnowledgeUrlJob copy(
            KnowledgeUrlJob job, long revision, Instant nextRefreshAt, Instant at) {
        return new KnowledgeUrlJob(
                job.id(), job.tenantId(), job.ownerId(), job.knowledgeDocumentId(),
                job.normalizedUrl(), job.origin(), job.refreshPolicy(), job.state(),
                revision, nextRefreshAt, job.createdAt(), at, job.archivedAt());
    }

    private static KnowledgeUrlEvidence.ContentVersion content(
            KnowledgeUrlEvidence.ContentVersion value,
            KnowledgeUrlEvidence.ContentState state,
            Instant activatedAt) {
        return new KnowledgeUrlEvidence.ContentVersion(
                value.id(), value.urlJobId(), value.knowledgeDocumentId(), value.tenantId(),
                value.version(), value.contentSha256(), value.objectReference(), value.mediaType(),
                value.charset(), value.byteSize(), state, value.sourceObservationId(),
                value.createdAt(), activatedAt);
    }
}
