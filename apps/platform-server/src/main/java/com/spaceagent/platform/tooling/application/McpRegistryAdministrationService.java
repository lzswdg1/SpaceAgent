package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpMarketplaceLifecycle;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpRegistryCandidate;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway;
import com.spaceagent.platform.tooling.domain.McpRegistryGatewayException;
import com.spaceagent.platform.tooling.domain.McpRegistryPublication;
import com.spaceagent.platform.tooling.domain.McpRegistryPublicationConflictException;
import com.spaceagent.platform.tooling.domain.McpRegistryRepository;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistrySource;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncJob;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class McpRegistryAdministrationService implements McpRegistryAdministrationApi {
    private static final String OFFICIAL_SOURCE = "official";

    private final McpRegistryRepository registry;
    private final McpMarketplaceRepository marketplace;
    private final McpRegistryGateway gateway;
    private final McpToolingProperties.Registry properties;
    private final IdGenerator ids;
    private final TimeProvider time;

    public McpRegistryAdministrationService(
            McpRegistryRepository registry,
            McpMarketplaceRepository marketplace,
            McpRegistryGateway gateway,
            McpToolingProperties properties,
            IdGenerator ids,
            TimeProvider time) {
        this.registry = registry;
        this.marketplace = marketplace;
        this.gateway = gateway;
        this.properties = properties.getRegistry();
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional
    public SyncJobView enqueue(String actorId) {
        requireUuid(actorId, "Administrator actor");
        McpRegistrySource source = source();
        if (!source.enabled()) throw conflict("Official MCP Registry synchronization is disabled",
                "MCP_REGISTRY_SOURCE_DISABLED");
        registry.findActiveJob(source.id()).ifPresent(value -> {
            throw conflict("An Official MCP Registry synchronization is already active",
                    "MCP_REGISTRY_SYNC_ACTIVE");
        });
        Instant now = time.now();
        McpRegistrySyncJob job = new McpRegistrySyncJob(
                ids.nextId(), source.id(), source.sourceKey(), actorId,
                McpRegistrySyncState.PENDING, source.lastSuccessfulSyncAt(), now,
                0, 0, 0, null, 0, null, null, null, now, null, now, null);
        try {
            registry.insertJob(job);
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            throw conflict("An Official MCP Registry synchronization is already active",
                    "MCP_REGISTRY_SYNC_ACTIVE");
        }
        return view(job);
    }

    @Override
    public boolean runOnce(String workerId) {
        String worker = workerId == null || workerId.isBlank()
                ? "mcp-registry:" + UUID.randomUUID() : workerId.trim();
        Instant now = time.now();
        String claimToken = ids.nextId();
        McpRegistrySyncJob job = registry.claim(
                worker, claimToken, now, now.plus(properties.getLeaseSeconds(), ChronoUnit.SECONDS),
                properties.getMaximumAttempts()).orElse(null);
        if (job == null) return false;
        try {
            List<McpRegistryRepository.SnapshotImport> imports = fetch(job, worker, claimToken);
            registry.complete(job.id(), worker, claimToken, imports, time.now());
        } catch (McpRegistryGatewayException error) {
            registry.fail(job.id(), worker, claimToken, safeCode(error.safeCode()), time.now());
        } catch (Exception error) {
            registry.fail(job.id(), worker, claimToken, "MCP_REGISTRY_SYNC_FAILED", time.now());
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public SystemAdministrationPage<SyncJobView> syncJobs(
            int page, int pageSize, McpRegistrySyncState state) {
        Page bound = page(page, pageSize);
        return new SystemAdministrationPage<>(
                registry.findJobs(bound.offset(), bound.size(), state).stream()
                        .map(this::view).toList(),
                bound.number(), bound.size(), registry.countJobs(state), time.now());
    }

    @Override
    @Transactional(readOnly = true)
    public SystemAdministrationPage<CandidateView> candidates(
            int page, int pageSize, McpRegistryReviewState state, String query) {
        Page bound = page(page, pageSize);
        String normalized = query == null ? null : query.trim();
        return new SystemAdministrationPage<>(
                registry.findCandidates(bound.offset(), bound.size(), state, normalized).stream()
                        .map(this::candidateView).toList(),
                bound.number(), bound.size(), registry.countCandidates(state, normalized), time.now());
    }

    @Override
    @Transactional(readOnly = true)
    public CandidateDetail candidate(String candidateId) {
        McpRegistryCandidate candidate = requireCandidate(candidateId);
        McpRegistrySnapshot snapshot = requireSnapshot(candidate.snapshotId());
        return detail(candidate, snapshot);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public ReviewResult approve(ReviewCommand command) {
        validateReview(command);
        McpRegistryCandidate candidate = requireCandidateForUpdate(command.candidateId());
        requirePending(candidate);
        McpRegistrySnapshot snapshot = requireSnapshot(candidate.snapshotId());
        if (snapshot.registryStatus() != McpRegistryStatus.ACTIVE) {
            throw conflict("Only an active Registry version can be approved",
                    "MCP_REGISTRY_SOURCE_NOT_ACTIVE");
        }
        if (snapshot.compatibility() != McpRegistryCompatibility.SUPPORTED_REMOTE
                || snapshot.transports().isEmpty()) {
            throw conflict("Registry version has no supported remote Streamable HTTP transport",
                    "MCP_REGISTRY_TRANSPORT_UNSUPPORTED");
        }
        Instant now = time.now();
        McpAuthType authType = snapshot.transports().stream()
                .anyMatch(McpRegistrySnapshot.RemoteTransport::secretHeaders)
                ? McpAuthType.CUSTOM : McpAuthType.NONE;
        String publisher = snapshot.registryName().substring(0, snapshot.registryName().indexOf('/'));
        List<McpRegistryPublication.Transport> transports = new ArrayList<>();
        for (int index = 0; index < snapshot.transports().size(); index++) {
            var transport = snapshot.transports().get(index);
            transports.add(new McpRegistryPublication.Transport(
                    ids.nextId(), index, transport.endpointUrl(),
                    transport.variablesJson(), transport.headersJson()));
        }
        McpRegistryPublication publication = new McpRegistryPublication(
                ids.nextId(), publisher, publisher, ids.nextId(), slug(snapshot.registryName()),
                snapshot.registryName(), name(snapshot), snapshot.description(), ids.nextId(),
                snapshot.registryVersion(), snapshot.repositoryUri(), snapshot.manifestSchemaUri(),
                snapshot.manifestJson(), snapshot.manifestSha256(), authType,
                McpMarketplaceLifecycle.ACTIVE, McpServerVersionState.APPROVED,
                snapshot.sourcePublishedAt(), now, transports);
        McpRegistryPublication.Result published;
        try {
            published = marketplace.publishRegistryVersion(publication);
        } catch (McpRegistryPublicationConflictException error) {
            throw conflict("Registry version conflicts with immutable Marketplace history",
                    "MCP_REGISTRY_VERSION_IMMUTABLE_CONFLICT");
        }
        if (!registry.markApproved(candidate.id(), candidate.revision(), command.actorId(),
                command.reason().trim(), published.entryId(), published.versionId(), now)) {
            throw new IllegalStateException("Locked MCP Registry candidate could not be approved");
        }
        return result(registry.findCandidate(candidate.id()).orElseThrow(), authType);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public ReviewResult reject(ReviewCommand command) {
        validateReview(command);
        McpRegistryCandidate candidate = requireCandidateForUpdate(command.candidateId());
        requirePending(candidate);
        Instant now = time.now();
        if (!registry.markRejected(candidate.id(), candidate.revision(), command.actorId(),
                command.reason().trim(), now)) {
            throw new IllegalStateException("Locked MCP Registry candidate could not be rejected");
        }
        return result(registry.findCandidate(candidate.id()).orElseThrow(), null);
    }

    private List<McpRegistryRepository.SnapshotImport> fetch(
            McpRegistrySyncJob job, String worker, String claimToken) {
        McpRegistrySource source = source();
        List<McpRegistryRepository.SnapshotImport> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> identities = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < properties.getMaximumPages(); page++) {
            McpRegistryGateway.RegistryPage remote = gateway.fetchPage(
                    source.baseUrl(), job.updatedSince(), cursor, properties.getPageSize());
            for (McpRegistryGateway.RegistryServer server : remote.servers()) {
                if (result.size() >= properties.getMaximumServers()) {
                    throw new McpRegistryGatewayException(
                            "MCP_REGISTRY_RESULT_LIMIT_EXCEEDED",
                            "Official MCP Registry result exceeded its bound");
                }
                String digest = sha256(server.sanitizedManifestJson());
                String identity = server.registryName() + "\n" + server.registryVersion() + "\n" + digest;
                if (!identities.add(identity)) continue;
                String snapshotId = ids.nextId();
                McpRegistrySnapshot snapshot = new McpRegistrySnapshot(
                        snapshotId, source.id(), job.id(), server.registryName(),
                        server.registryVersion(), server.status(), server.statusMessage(),
                        server.title(), server.description(), server.manifestSchemaUri(),
                        server.repositoryUri(), server.sanitizedManifestJson(), digest,
                        server.compatibility(), server.compatibilityReason(), server.publishedAt(),
                        server.updatedAt(), time.now(), server.transports());
                result.add(new McpRegistryRepository.SnapshotImport(
                        snapshotId, ids.nextId(), snapshot));
            }
            String next = remote.nextCursor();
            if (next == null || next.isBlank()) return result;
            Instant heartbeat = time.now();
            if (!registry.renewLease(job.id(), worker, claimToken, heartbeat,
                    heartbeat.plus(properties.getLeaseSeconds(), ChronoUnit.SECONDS))) {
                throw new McpRegistryGatewayException(
                        "MCP_REGISTRY_LEASE_LOST", "MCP Registry synchronization lease was lost");
            }
            if (!cursors.add(next)) {
                throw new McpRegistryGatewayException(
                        "MCP_REGISTRY_CURSOR_LOOP", "Official MCP Registry cursor repeated");
            }
            cursor = next;
        }
        throw new McpRegistryGatewayException(
                "MCP_REGISTRY_PAGE_LIMIT_EXCEEDED",
                "Official MCP Registry pagination exceeded its bound");
    }

    private CandidateDetail detail(McpRegistryCandidate candidate, McpRegistrySnapshot snapshot) {
        return new CandidateDetail(
                candidateView(candidate, snapshot), snapshot.title(), snapshot.description(),
                snapshot.statusMessage(), snapshot.manifestSchemaUri(), snapshot.repositoryUri(),
                snapshot.manifestJson(), snapshot.sourcePublishedAt(),
                snapshot.transports().stream().map(value -> new TransportView(
                        value.endpointUrl(), value.variablesJson(), value.headersJson(),
                        value.secretHeaders())).toList(), candidate.reviewedBy(),
                candidate.reviewReason(), candidate.reviewedAt());
    }

    private CandidateView candidateView(McpRegistryCandidate candidate) {
        return candidateView(candidate, requireSnapshot(candidate.snapshotId()));
    }

    private static CandidateView candidateView(
            McpRegistryCandidate candidate, McpRegistrySnapshot snapshot) {
        return new CandidateView(
                candidate.id(), candidate.sourceKey(), candidate.registryName(),
                candidate.registryVersion(), candidate.reviewState(), snapshot.registryStatus(),
                snapshot.compatibility(), snapshot.compatibilityReason(), snapshot.manifestSha256(),
                candidate.publishedEntryId(), candidate.publishedVersionId(), candidate.revision(),
                snapshot.sourceUpdatedAt(), candidate.createdAt(), candidate.updatedAt());
    }

    private static ReviewResult result(McpRegistryCandidate value, McpAuthType authType) {
        return new ReviewResult(
                value.id(), value.reviewState(), value.registryName(), value.registryVersion(),
                value.publishedEntryId(), value.publishedVersionId(), authType,
                value.revision(), value.reviewedAt());
    }

    private SyncJobView view(McpRegistrySyncJob value) {
        return new SyncJobView(
                value.id(), value.sourceKey(), value.requestedBy(), value.state(),
                value.updatedSince(), value.watermarkAt(), value.fetchedCount(),
                value.snapshotCount(), value.candidateCount(), value.safeErrorCode(),
                value.attempt(), value.createdAt(), value.startedAt(), value.updatedAt(),
                value.completedAt());
    }

    private McpRegistrySource source() {
        return registry.findSource(OFFICIAL_SOURCE).orElseThrow(() -> conflict(
                "Official MCP Registry source is unavailable", "MCP_REGISTRY_SOURCE_NOT_FOUND"));
    }

    private McpRegistryCandidate requireCandidate(String id) {
        requireUuid(id, "Registry candidate");
        return registry.findCandidate(id).orElseThrow(() -> new BusinessException(
                "Registry candidate not found", HttpStatus.NOT_FOUND,
                "MCP_REGISTRY_CANDIDATE_NOT_FOUND"));
    }

    private McpRegistryCandidate requireCandidateForUpdate(String id) {
        requireUuid(id, "Registry candidate");
        return registry.findCandidateForUpdate(id).orElseThrow(() -> new BusinessException(
                "Registry candidate not found", HttpStatus.NOT_FOUND,
                "MCP_REGISTRY_CANDIDATE_NOT_FOUND"));
    }

    private McpRegistrySnapshot requireSnapshot(String id) {
        return registry.findSnapshot(id).orElseThrow(() -> new IllegalStateException(
                "Registry candidate snapshot is missing"));
    }

    private static void validateReview(ReviewCommand command) {
        if (command == null) throw invalid("Registry review is required");
        requireUuid(command.candidateId(), "Registry candidate");
        requireUuid(command.actorId(), "Administrator actor");
        if (command.reason() == null || command.reason().isBlank()
                || command.reason().trim().length() > 500) {
            throw invalid("Registry review reason is required and must not exceed 500 characters");
        }
    }

    private static void requirePending(McpRegistryCandidate candidate) {
        if (candidate.reviewState() != McpRegistryReviewState.PENDING_REVIEW) {
            throw conflict("Registry candidate was already reviewed", "MCP_REGISTRY_ALREADY_REVIEWED");
        }
    }

    private static void requireUuid(String value, String noun) {
        try {
            UUID.fromString(value);
        } catch (Exception error) {
            throw invalid(noun + " identifier is invalid");
        }
    }

    private static Page page(int number, int size) {
        int boundedNumber = Math.max(0, number);
        int boundedSize = Math.max(1, Math.min(100, size));
        long offset = (long) boundedNumber * boundedSize;
        return new Page(boundedNumber, boundedSize,
                (int) Math.min(Integer.MAX_VALUE, offset));
    }

    private static String name(McpRegistrySnapshot value) {
        if (value.title() != null && !value.title().isBlank()) return value.title();
        String name = value.registryName().substring(value.registryName().indexOf('/') + 1);
        return name.length() <= 120 ? name : name.substring(0, 120);
    }

    private static String slug(String registryName) {
        String normalized = registryName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        String suffix = sha256(registryName).substring(0, 10);
        int maximumPrefix = 80 - suffix.length() - 1;
        if (normalized.length() > maximumPrefix) normalized = normalized.substring(0, maximumPrefix);
        return normalized + "-" + suffix;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash MCP Registry evidence", error);
        }
    }

    private static String safeCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,100}")) return "MCP_REGISTRY_SYNC_FAILED";
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "MCP_REGISTRY_INPUT_INVALID");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record Page(int number, int size, int offset) {
    }
}
