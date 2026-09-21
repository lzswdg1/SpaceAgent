package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.Objects;

/** Exact owner-resource reference; owner modules persist only objectId, never storage coordinates. */
public record ArtifactObjectReference(String id,String tenantId,String objectId,OwnerType ownerType,
        String ownerResourceId,String purpose,State state,long revision,Instant createdAt,Instant releasedAt){
    public ArtifactObjectReference{ManagedArtifactObject.require(id,"id");ManagedArtifactObject.require(tenantId,"tenantId");ManagedArtifactObject.require(objectId,"objectId");Objects.requireNonNull(ownerType);ManagedArtifactObject.require(ownerResourceId,"ownerResourceId");ManagedArtifactObject.require(purpose,"purpose");Objects.requireNonNull(state);Objects.requireNonNull(createdAt);if(revision<=0||(state==State.RELEASED)!=(releasedAt!=null))throw new IllegalArgumentException("Artifact object reference is invalid");}
    public ArtifactObjectReference release(Instant at){if(state==State.RELEASED)return this;return new ArtifactObjectReference(id,tenantId,objectId,ownerType,ownerResourceId,purpose,State.RELEASED,revision+1,createdAt,at);}
    public enum OwnerType{ARTIFACT,PROJECT,KNOWLEDGE}
    public enum State{ACTIVE,RELEASED}
}
