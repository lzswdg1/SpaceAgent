package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArtifactObjectRepository {
    Instant currentTime();
    void insertStaging(ArtifactObjectStagingSession session);
    default void insertStaging(
            ArtifactObjectStagingSession session,
            StagingQuota quota) {
        insertStaging(session);
    }
    Optional<ArtifactObjectStagingSession> findStaging(String tenantId,String stagingId);
    Optional<ArtifactObjectStagingSession> findStagingByRequest(String tenantId,String actorUserId,String requestId);
    Optional<ArtifactObjectStagingSession> updateStaging(ArtifactObjectStagingSession session,long expectedRevision);
    default PublishResult publish(
            String tenantId,String stagingId,long expectedRevision,
            ManagedArtifactObject proposedObject,Instant at) {
        return publish(tenantId, stagingId, expectedRevision, proposedObject, at, Long.MAX_VALUE);
    }
    PublishResult publish(String tenantId,String stagingId,long expectedRevision,
            ManagedArtifactObject proposedObject,Instant at,long maximumStoredBytesPerTenant);
    Optional<ManagedArtifactObject> findObject(String tenantId,String objectId);
    Optional<ManagedArtifactObject> updateObject(ManagedArtifactObject object,long expectedRevision);
    void insertReference(ArtifactObjectReference reference);
    Optional<ArtifactObjectReference> findReference(String tenantId,String referenceId);
    Optional<ArtifactObjectReference> updateReference(ArtifactObjectReference reference,long expectedRevision);
    List<ArtifactObjectReference> references(String tenantId,String objectId);
    void insertHold(ArtifactObjectLegalHold hold);
    Optional<ArtifactObjectLegalHold> findHold(String tenantId,String holdId);
    Optional<ArtifactObjectLegalHold> updateHold(ArtifactObjectLegalHold hold,long expectedRevision);
    List<ArtifactObjectLegalHold> holds(String tenantId,String objectId);
    void insertDeletion(ArtifactObjectDeletionJob job);
    Optional<ArtifactObjectDeletionJob> scheduleDeletion(ManagedArtifactObject object,long expectedObjectRevision,
            ArtifactObjectDeletionJob job);
    Optional<ArtifactObjectDeletionJob> findDeletion(String tenantId,String objectId);
    Optional<ArtifactObjectDeletionJob> claimDeletion(String workerId,String claimToken,int leaseSeconds);
    Optional<ArtifactObjectDeletionJob> finishDeletion(String tenantId,String jobId,long expectedRevision,
            String claimToken,long fencingToken,ManagedArtifactObject object,long expectedObjectRevision,
            ArtifactObjectDeletionJob.State state,String safeErrorCode,Instant at);
    List<ArtifactObjectStagingSession> findExpiredStaging(Instant at,int limit);
    Optional<ArtifactObjectDeletionJob> recoverExpiredDeletion(Instant at);
    Optional<ArtifactObjectDeletionJob> retryBlockedDeletion(
            String tenantId,String objectId,Instant at);
    record PublishResult(ArtifactObjectStagingSession staging,ManagedArtifactObject object,
            boolean reusedExisting,String redundantStagingReference){}
    record StagingQuota(
            int maximumSessionsPerUser,
            long maximumBytesPerUser,
            int maximumSessionsPerTenant,
            long maximumBytesPerTenant,
            long maximumStoredBytesPerTenant) {
        public StagingQuota {
            if (maximumSessionsPerUser < 1 || maximumBytesPerUser < 1
                    || maximumSessionsPerTenant < maximumSessionsPerUser
                    || maximumBytesPerTenant < maximumBytesPerUser
                    || maximumStoredBytesPerTenant < maximumBytesPerUser) {
                throw new IllegalArgumentException("Artifact staging quota is invalid");
            }
        }
    }
}
