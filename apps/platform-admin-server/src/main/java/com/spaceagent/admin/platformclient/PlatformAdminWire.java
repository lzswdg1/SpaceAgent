package com.spaceagent.admin.platformclient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PlatformAdminWire {
    public record PresenceSummary(Long onlineUsers, Long onlineSessions, long observedSessionCount,
                                  String coverage, String definition, int leaseSeconds, Instant measuredAt) { }
    private PlatformAdminWire() {}

    public record Envelope<T>(boolean success, String code, String message, T data) {
    }

    public record Page<T>(List<T> items, int page, int pageSize, long total, Instant generatedAt) {
        public Page { items = items == null ? List.of() : List.copyOf(items); }
    }

    public record Health(String status, String releaseVersion, int schemaVersion, Instant generatedAt) {
    }

    public record IdentityOverview(long totalUsers, long pendingUsers, long activeUsers,
                                   long suspendedUsers, long deletionPendingUsers, long deletedUsers,
                                   long registeredInWindow, long uniqueSuccessfulLoginsInWindow,
                                   long recentlyActiveUsers, long activeRefreshSessions,
                                   long activeOrganizations, long deletingOrganizations) {
    }

    public record InferenceOverview(long providers, long activeProviders, long unhealthyProviders,
                                    long untestedProviders, long disabledProviders,
                                    long modelPools, long activeModelPools, Long unknownModelCalls) {
        public InferenceOverview(long providers, long activeProviders, long unhealthyProviders,
                long untestedProviders, long disabledProviders, long modelPools, long activeModelPools) {
            this(providers, activeProviders, unhealthyProviders, untestedProviders, disabledProviders,
                    modelPools, activeModelPools, null);
        }
    }

    public record AgentOverview(long agents, long activeAgents, long activeApiKeys) {
    }

    public record ProjectOverview(long projects, long activeProjects, long workspaces,
                                  long activeWorkspaces) {
    }

    public record ConversationOverview(long conversations, long activeConversations, long messages) {
    }

    public record RuntimeOverview(long runs, long activeRuns, long completedRuns, long failedRuns,
                                  long cancelledRuns, long recoveringRuns, Long unknownRuns) {
        public RuntimeOverview(long runs, long activeRuns, long completedRuns, long failedRuns,
                long cancelledRuns, long recoveringRuns, long unknownRuns) {
            this(runs, activeRuns, completedRuns, failedRuns, cancelledRuns, recoveringRuns, Long.valueOf(unknownRuns));
        }
    }

    public record ToolingOverview(long mcpConnections, long activeMcpConnections,
                                  long unknownToolExecutions) {
    }

    public record Overview(String window, Instant generatedAt, String releaseVersion,
                           int schemaVersion, String platformReadiness, IdentityOverview identity,
                           InferenceOverview inference, AgentOverview agent, ProjectOverview project,
                           ConversationOverview conversation, RuntimeOverview runtime,
                           ToolingOverview tooling) {
    }

    public record UserSummary(String id, String primaryOrganizationId, String loginName,
                              String displayName, String status, boolean mustChangePassword,
                              Instant createdAt, Instant updatedAt, Instant lastLoginAt,
                              Instant lastSeenAt, long successfulLoginCount,
                              long activeRefreshSessions, long membershipCount) {
    }

    public record UserMembership(String organizationId, String organizationName,
                                 String organizationStatus, String role, String membershipStatus,
                                 Instant joinedAt) {
    }

    public record UserDetail(UserSummary user, List<UserMembership> memberships) {
        public UserDetail { memberships = memberships == null ? List.of() : List.copyOf(memberships); }
    }

    public record OrganizationSummary(String id, String name, String slug, String status,
                                      String creatorUserId, String creatorDisplayName,
                                      long activeMembers, Instant createdAt, Instant updatedAt,
                                      Instant deletionRequestedAt) {
    }

    public record OrganizationMemberSummary(
            String organizationId, String userId, String loginName, String displayName,
            String userStatus, String role, String membershipStatus,
            Instant joinedAt, Instant updatedAt) {
    }

    public record AgentSummary(String id, String organizationId, String ownerUserId, String name,
                               String description, String status,
                               long revision, long activeKeyCount, Instant createdAt,
                               Instant updatedAt, Instant archivedAt) {
    }

    public record UserResourceSummary(
            String kind, String id, String organizationId, String parentId, String displayName,
            String state, String relation, Instant createdAt, Instant updatedAt,
            String safeErrorCode, long primaryCount, long secondaryCount) {
    }

    public record UserResourceOverview(
            long modelPools, long projects, long tasks, long workspaces, long conversations,
            long mcpConnections, long knowledgeDocuments, long memories, long automations,
            long runs, long modelEffects, long toolEffects) {
    }

    public record CredentialItem(String kind, String id, String organizationId, String ownerUserId,
                                 String subjectId, String name, String baseUrl, String endpointHost,
                                 String authType, boolean secretConfigured, String secretHint,
                                 String secretFingerprint, String secretKeyVersion, String status,
                                 Instant observedAt, Map<String, Object> metadata) {
        public CredentialItem { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }

    public record CommandView(java.util.UUID commandId, String operation, String targetUserId,
                              String state, Map<String, Object> result, String safeErrorCode,
                              String activationToken, Instant createdAt, Instant updatedAt,
                              Instant completedAt, String passwordResetToken) {
        public CommandView(java.util.UUID commandId, String operation, String targetUserId, String state,
                Map<String,Object> result, String safeErrorCode, String activationToken, Instant createdAt,
                Instant updatedAt, Instant completedAt) {
            this(commandId, operation, targetUserId, state, result, safeErrorCode, activationToken,
                    createdAt, updatedAt, completedAt, null);
        }
        public CommandView { result = result == null ? Map.of() : Map.copyOf(result); }
    }

    public record UserCleanupJob(String userId, java.util.UUID commandId,
                                 java.util.UUID requestedBy, String state,
                                 Instant retentionNotBefore, Instant nextAttemptAt,
                                 int attempt, int maxAttempts, String leaseOwner,
                                 String leaseToken, long fencingToken, Instant leaseUntil,
                                 String lastErrorCode, String lastErrorSummary, long revision,
                                 Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record UserCleanupStep(String userId, String stepKey, int sequence, String state,
                                  int attempt, String lastErrorCode, String lastErrorSummary,
                                  Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record UserCleanupJobProjection(UserCleanupJob job, List<UserCleanupStep> steps) {
        public UserCleanupJobProjection { steps = steps == null ? List.of() : List.copyOf(steps); }
    }

    public record CleanupJobSummary(String kind, String subjectId, String state, String commandId,
                                    String requestedBy, Instant retentionNotBefore,
                                    Instant nextAttemptAt, int attempt, int maxAttempts,
                                    String lastErrorCode, String lastErrorSummary, long revision,
                                    long completedSteps, long totalSteps, String currentStepKey,
                                    Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record CleanupStepSummary(String stepKey, int sequence, String state, int attempt,
                                     String lastErrorCode, String lastErrorSummary,
                                     Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record CleanupJobDetail(CleanupJobSummary job, List<CleanupStepSummary> steps) {
        public CleanupJobDetail { steps = steps == null ? List.of() : List.copyOf(steps); }
    }

    public record CleanupOverview(long pending, long claimed, long retry, long blocked,
                                  long completed, List<BlockerCount> blockers) {
        public CleanupOverview { blockers = blockers == null ? List.of() : List.copyOf(blockers); }
    }

    public record BlockerCount(String code, long count) {
    }

    public record McpRegistrySyncJob(
            String id, String sourceKey, String requestedBy, String state,
            Instant updatedSince, Instant watermarkAt, int fetchedCount,
            int snapshotCount, int candidateCount, String safeErrorCode, int attempt,
            Instant createdAt, Instant startedAt, Instant updatedAt, Instant completedAt) {
    }

    public record McpRegistryCandidate(
            String id, String sourceKey, String registryName, String registryVersion,
            String reviewState, String registryStatus, String compatibility,
            String compatibilityReason, String manifestSha256, String publishedEntryId,
            String publishedVersionId, long revision, Instant sourceUpdatedAt,
            Instant createdAt, Instant updatedAt) {
    }

    public record McpRegistryTransport(
            String endpointUrl, String variablesJson, String headersJson,
            boolean secretHeaders) {
    }

    public record McpRegistryCandidateDetail(
            McpRegistryCandidate candidate, String title, String description,
            String statusMessage, String manifestSchemaUri, String repositoryUri,
            String manifestJson, Instant sourcePublishedAt,
            List<McpRegistryTransport> transports, String reviewedBy,
            String reviewReason, Instant reviewedAt) {
        public McpRegistryCandidateDetail {
            transports = transports == null ? List.of() : List.copyOf(transports);
        }
    }
}
