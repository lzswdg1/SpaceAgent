package com.spaceagent.platform.project.domain;
public interface WorkspaceProvisioningGateway {
    default void initializeEmptySource(SourceRepository source) { throw new UnsupportedOperationException("Managed root storage unavailable"); }
    ProvisionedWorkspace provision(
            Workspace workspace, SourceRepository source, String authorizationHeader);
    void cleanup(Workspace workspace, SourceRepository source);
    default java.util.List<String> branches(String sourceId) { return java.util.List.of(); }
    default void cleanupSource(SourceRepository source) {
        // Compatibility gateways own no managed mirror beyond individual workspaces.
    }
    record ProvisionedWorkspace(String locator, String headCommit) { }
}
