package com.spaceagent.platform.artifact.domain;

/** Raised under the repository quota lock before Artifact bytes can be reserved or retained. */
public class ArtifactObjectQuotaExceededException extends RuntimeException {
    private final Limit limit;

    public ArtifactObjectQuotaExceededException(Limit limit) {
        super("Artifact object quota exceeded: " + limit.name());
        this.limit = limit;
    }

    public Limit limit() {
        return limit;
    }

    public enum Limit {
        USER_STAGING,
        TENANT_STAGING,
        TENANT_STORAGE
    }
}
