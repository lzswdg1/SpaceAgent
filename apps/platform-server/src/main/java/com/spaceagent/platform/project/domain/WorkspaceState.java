package com.spaceagent.platform.project.domain;

/**
 * Lifecycle state of a {@link Workspace}.
 */
public enum WorkspaceState {
    PROVISIONING,
    WAITING_FOR_BRIDGE,
    READY,
    DIRTY,
    CLEANED_UP,
    ARCHIVED,
    FAILED
}
