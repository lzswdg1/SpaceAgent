package com.spaceagent.platform.project.domain;

/**
 * Project-owned disposable source checkout used only before authoritative Tasks exist.
 * Implementations must create an exact {@code workspaces/{intakeJobId}} root and support
 * idempotent cleanup.
 */
public interface ProjectIntakeWorkspaceGateway {

    IntakeWorkspace provision(
            String intakeJobId,
            SourceRepository source,
            String baseRef,
            String authorizationHeader);

    void cleanup(String intakeJobId, SourceRepository source);

    record IntakeWorkspace(String workspaceRef, String headCommit) {
    }
}
