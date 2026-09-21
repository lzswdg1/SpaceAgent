package com.spaceagent.platform.project.domain;

/** Project-owned lifecycle for a local Bridge source snapshot; READY is impossible before materialization. */
public enum ProjectLocalMaterializationSessionState {
    OPEN,
    UPLOADING,
    VERIFIED,
    MATERIALIZED,
    EXPIRED,
    CANCELLED,
    BLOCKED
}
