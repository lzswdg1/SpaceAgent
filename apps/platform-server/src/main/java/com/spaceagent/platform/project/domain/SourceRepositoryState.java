package com.spaceagent.platform.project.domain;

/**
 * Lifecycle state of a {@link SourceRepository}.
 */
public enum SourceRepositoryState {
    PROVISIONING,
    READY,
    DIRTY,
    FAILED,
    ARCHIVED
}
