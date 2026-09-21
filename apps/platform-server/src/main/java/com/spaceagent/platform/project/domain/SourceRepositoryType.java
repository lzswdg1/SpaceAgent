package com.spaceagent.platform.project.domain;

/**
 * The kind of source-code repository backing a {@link SourceRepository}.
 */
public enum SourceRepositoryType {
    LOCAL,
    MANAGED_SNAPSHOT,
    GIT,
    GITHUB,
    GITLAB,
    GENERIC
}
