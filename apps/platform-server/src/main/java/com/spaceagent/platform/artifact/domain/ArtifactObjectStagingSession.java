package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.Objects;

/** Bytes remain invisible to owner modules until exact verification and atomic publish. */
public record ArtifactObjectStagingSession(
        String id,String tenantId,String requestedBy,String requestId,String expectedSha256,
        long expectedBytes,String mediaType,String encryptionReference,String stagingReference,
        State state,String publishedObjectId,long revision,Instant expiresAt,Instant createdAt,Instant updatedAt) {
    public ArtifactObjectStagingSession {ManagedArtifactObject.require(id,"id");ManagedArtifactObject.require(tenantId,"tenantId");ManagedArtifactObject.require(requestedBy,"requestedBy");ManagedArtifactObject.require(requestId,"requestId");ManagedArtifactObject.hash(expectedSha256);ManagedArtifactObject.opaque(encryptionReference,"tenant-key:");Objects.requireNonNull(state);Objects.requireNonNull(expiresAt);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);if(expectedBytes<0||expectedBytes>ManagedArtifactObject.MAX_BYTES||mediaType==null||mediaType.isBlank()||mediaType.length()>160||revision<=0||(state==State.OPEN&&stagingReference!=null)||(state!=State.OPEN&&stagingReference==null)||(state==State.PUBLISHED)!=(publishedObjectId!=null))throw new IllegalArgumentException("Artifact staging session is invalid");if(stagingReference!=null)ManagedArtifactObject.opaque(stagingReference,"artifact-staging:");}
    public ArtifactObjectStagingSession provision(String stagedRef,Instant at){if(state!=State.OPEN||!at.isBefore(expiresAt))throw new IllegalStateException("Artifact staging provisioning failed");ManagedArtifactObject.opaque(stagedRef,"artifact-staging:");return copy(stagedRef,State.UPLOADING,null,at);}
    public ArtifactObjectStagingSession verify(String actualSha256,long actualBytes,String stagedRef,Instant at){if(!java.util.List.of(State.OPEN,State.UPLOADING).contains(state)||!expectedSha256.equals(actualSha256)||expectedBytes!=actualBytes||!at.isBefore(expiresAt))throw new IllegalStateException("Artifact staging verification failed");ManagedArtifactObject.opaque(stagedRef,"artifact-staging:");return copy(stagedRef,State.VERIFIED,null,at);}
    public ArtifactObjectStagingSession publish(String objectId,Instant at){if(state!=State.VERIFIED||!at.isBefore(expiresAt))throw new IllegalStateException("Only verified staging can publish");ManagedArtifactObject.require(objectId,"objectId");return copy(stagingReference,State.PUBLISHED,objectId,at);}
    public ArtifactObjectStagingSession reject(String stagedRef,Instant at){if(state==State.PUBLISHED)throw new IllegalStateException("Published staging is immutable");return copy(stagedRef,State.REJECTED,null,at);}
    public ArtifactObjectStagingSession unknown(String stagedRef,Instant at){if(state==State.PUBLISHED)throw new IllegalStateException("Published staging is immutable");return copy(stagedRef,State.UNKNOWN,null,at);}
    private ArtifactObjectStagingSession copy(String staged,State next,String object,Instant at){return new ArtifactObjectStagingSession(id,tenantId,requestedBy,requestId,expectedSha256,expectedBytes,mediaType,encryptionReference,staged,next,object,revision+1,expiresAt,createdAt,at);}
    public enum State{OPEN,UPLOADING,VERIFIED,PUBLISHED,REJECTED,UNKNOWN}
}
