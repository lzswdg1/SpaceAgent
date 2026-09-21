package com.spaceagent.platform.project.domain;

/** Lifecycle of Project's independent SourceMerge reconciliation aggregate. */
public enum ProjectReconciliationStepState {
    WAITING_RECONCILIATION,
    PROPOSAL_READY,
    BLOCKED,
    RESOLVED
}
