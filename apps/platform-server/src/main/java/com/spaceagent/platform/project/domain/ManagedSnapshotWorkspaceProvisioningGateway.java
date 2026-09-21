package com.spaceagent.platform.project.domain;

public interface ManagedSnapshotWorkspaceProvisioningGateway {
    WorkspaceProvisioningGateway.ProvisionedWorkspace provision(Workspace workspace, SourceRepository source);
}
