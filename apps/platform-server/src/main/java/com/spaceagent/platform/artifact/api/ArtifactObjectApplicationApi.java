package com.spaceagent.platform.artifact.api;

import com.spaceagent.platform.artifact.domain.ArtifactObjectReference;
import java.time.Instant;
import java.util.List;

/** Public metadata contract. Storage adapters receive no owner-module authority or credentials. */
public interface ArtifactObjectApplicationApi {
    StagingView beginStaging(BeginStagingCommand command);
    StagingView getStaging(GetStagingQuery query);
    StagingView upload(UploadChunkCommand command);
    StagingView verify(VerifyStagingCommand command);
    ObjectView publish(PublishStagingCommand command);
    ObjectView get(GetObjectQuery query);
    ObjectBytesView read(ReadObjectQuery query);
    DownloadCapabilityView createDownloadCapability(DownloadCapabilityCommand command);
    ReferenceView attach(AttachReferenceCommand command);
    ReferenceView release(ReleaseReferenceCommand command);
    HoldView placeHold(PlaceHoldCommand command);
    HoldView releaseHold(ReleaseHoldCommand command);
    ObjectView requestDeletion(RequestDeletionCommand command);

    record BeginStagingCommand(String tenantId,String actorUserId,String requestId,String expectedSha256,
            long expectedBytes,String mediaType,int ttlSeconds){}
    record GetStagingQuery(String tenantId,String actorUserId,String stagingId){}
    record GetObjectQuery(String tenantId,String actorUserId,String objectId){}
    record UploadChunkCommand(String tenantId,String actorUserId,String stagingId,long expectedOffset,String chunkBase64){}
    record VerifyStagingCommand(String tenantId,String actorUserId,String stagingId,long expectedRevision){}
    record PublishStagingCommand(String tenantId,String actorUserId,String stagingId,long expectedRevision,
            Instant retainUntil,boolean deleteWhenUnreferenced){}
    record ReadObjectQuery(String tenantId,String actorUserId,String objectId,long offset,int maximumBytes){}
    record DownloadCapabilityCommand(String tenantId,String actorUserId,String objectId,int ttlSeconds){}
    record AttachReferenceCommand(String tenantId,String actorUserId,String objectId,
            ArtifactObjectReference.OwnerType ownerType,String ownerResourceId,String purpose,String requestId){}
    record ReleaseReferenceCommand(String tenantId,String actorUserId,String referenceId,long expectedRevision){}
    record PlaceHoldCommand(String tenantId,String actorUserId,String objectId,String reasonSha256,String requestId){}
    record ReleaseHoldCommand(String tenantId,String actorUserId,String holdId,long expectedRevision){}
    record RequestDeletionCommand(String tenantId,String actorUserId,String objectId,long expectedRevision,String requestId){}
    record StagingView(String id,String expectedSha256,long expectedBytes,String mediaType,String state,
            String publishedObjectId,long revision,Instant expiresAt,Instant createdAt,Instant updatedAt){}
    record ObjectView(String id,String contentSha256,long byteSize,String mediaType,String state,
            Instant retainUntil,boolean deleteWhenUnreferenced,long activeReferences,boolean legalHold,
            long revision,Instant createdAt,Instant updatedAt,Instant deletedAt){}
    record ReferenceView(String id,String objectId,String ownerType,String ownerResourceId,String purpose,
            String state,long revision,Instant createdAt,Instant releasedAt){}
    record HoldView(String id,String objectId,String reasonSha256,String state,long revision,
            Instant placedAt,Instant releasedAt){}
    record ObjectBytesView(String objectId,String contentBase64,long totalBytes,boolean truncated){}
    record DownloadCapabilityView(String objectId,String url,Instant expiresAt){}
}
