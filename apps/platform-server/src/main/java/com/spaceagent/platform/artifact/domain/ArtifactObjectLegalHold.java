package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.Objects;

public record ArtifactObjectLegalHold(String id,String tenantId,String objectId,String reasonSha256,
        String placedBy,State state,long revision,Instant placedAt,String releasedBy,Instant releasedAt){
    public ArtifactObjectLegalHold{ManagedArtifactObject.require(id,"id");ManagedArtifactObject.require(tenantId,"tenantId");ManagedArtifactObject.require(objectId,"objectId");ManagedArtifactObject.hash(reasonSha256);ManagedArtifactObject.require(placedBy,"placedBy");Objects.requireNonNull(state);Objects.requireNonNull(placedAt);if(revision<=0||(state==State.RELEASED)!=(releasedBy!=null&&releasedAt!=null))throw new IllegalArgumentException("Artifact legal hold is invalid");}
    public ArtifactObjectLegalHold release(String actor,Instant at){if(state==State.RELEASED)return this;ManagedArtifactObject.require(actor,"releasedBy");return new ArtifactObjectLegalHold(id,tenantId,objectId,reasonSha256,placedBy,State.RELEASED,revision+1,placedAt,actor,at);}
    public enum State{ACTIVE,RELEASED}
}
