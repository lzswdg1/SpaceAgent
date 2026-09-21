package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;

import java.time.Instant;
import java.util.List;

public interface McpRegistryAdministrationApi {
    SyncJobView enqueue(String actorId);

    boolean runOnce(String workerId);

    SystemAdministrationPage<SyncJobView> syncJobs(
            int page, int pageSize, McpRegistrySyncState state);

    SystemAdministrationPage<CandidateView> candidates(
            int page, int pageSize, McpRegistryReviewState state, String query);

    CandidateDetail candidate(String candidateId);

    ReviewResult approve(ReviewCommand command);

    ReviewResult reject(ReviewCommand command);

    record ReviewCommand(String candidateId, String actorId, String reason) {
    }

    record SyncJobView(
            String id,
            String sourceKey,
            String requestedBy,
            McpRegistrySyncState state,
            Instant updatedSince,
            Instant watermarkAt,
            int fetchedCount,
            int snapshotCount,
            int candidateCount,
            String safeErrorCode,
            int attempt,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt) {
    }

    record CandidateView(
            String id,
            String sourceKey,
            String registryName,
            String registryVersion,
            McpRegistryReviewState reviewState,
            McpRegistryStatus registryStatus,
            McpRegistryCompatibility compatibility,
            String compatibilityReason,
            String manifestSha256,
            String publishedEntryId,
            String publishedVersionId,
            long revision,
            Instant sourceUpdatedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    record CandidateDetail(
            CandidateView candidate,
            String title,
            String description,
            String statusMessage,
            String manifestSchemaUri,
            String repositoryUri,
            String manifestJson,
            Instant sourcePublishedAt,
            List<TransportView> transports,
            String reviewedBy,
            String reviewReason,
            Instant reviewedAt) {

        public CandidateDetail {
            transports = transports == null ? List.of() : List.copyOf(transports);
        }
    }

    record TransportView(
            String endpointUrl,
            String variablesJson,
            String headersJson,
            boolean secretHeaders) {
    }

    record ReviewResult(
            String candidateId,
            McpRegistryReviewState reviewState,
            String registryName,
            String registryVersion,
            String publishedEntryId,
            String publishedVersionId,
            McpAuthType authType,
            long revision,
            Instant reviewedAt) {
    }
}
