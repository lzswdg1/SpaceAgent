package com.spaceagent.platform.project.domain;

public enum SourceMergeState {
    PREPARING,
    READY,
    APPLYING,
    APPLIED_LOCAL,
    ROLLING_BACK,
    ROLLED_BACK,
    CONFLICT,
    UNKNOWN,
    FAILED
}
