package com.spaceagent.platform.artifact.domain;

/** Cross-module ownership proof implemented by Integration through public owner APIs only. */
public interface ArtifactObjectOwnerScopeGateway {
    boolean canAttach(String tenantId,String actorUserId,ArtifactObjectReference.OwnerType type,String resourceId);
}
