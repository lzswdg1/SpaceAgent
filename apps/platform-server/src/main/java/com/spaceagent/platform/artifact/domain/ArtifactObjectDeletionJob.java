package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable, fenced bytes-first deletion request. */
public record ArtifactObjectDeletionJob(String id,String tenantId,String objectId,State state,
        String claimToken,String claimOwner,Instant leaseUntil,long fencingToken,String safeErrorCode,
        long revision,Instant createdAt,Instant updatedAt,Instant completedAt){
    public ArtifactObjectDeletionJob{ManagedArtifactObject.require(id,"id");ManagedArtifactObject.require(tenantId,"tenantId");ManagedArtifactObject.require(objectId,"objectId");Objects.requireNonNull(state);Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);if(revision<=0||fencingToken<0||(state==State.CLAIMED)!=(claimToken!=null&&claimOwner!=null&&leaseUntil!=null)||(state==State.COMPLETED)!=(completedAt!=null)||(safeErrorCode!=null&&!safeErrorCode.matches("[A-Z0-9_-]{1,120}")))throw new IllegalArgumentException("Artifact deletion job is invalid");}
    public ArtifactObjectDeletionJob retry(Instant at){if(state!=State.BLOCKED)throw new IllegalStateException("Only blocked Artifact deletion can be retried");return new ArtifactObjectDeletionJob(id,tenantId,objectId,State.PENDING,null,null,null,fencingToken,null,revision+1,createdAt,at,null);}
    public enum State{PENDING,CLAIMED,BLOCKED,COMPLETED}
}
