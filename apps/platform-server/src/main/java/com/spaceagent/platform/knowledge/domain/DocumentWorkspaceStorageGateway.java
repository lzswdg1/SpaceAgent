package com.spaceagent.platform.knowledge.domain;

/** Provisions only an empty managed namespace; document bytes are accessed only through OCI Sandbox tools. */
public interface DocumentWorkspaceStorageGateway {
    void provision(DocumentWorkspace workspace);
    default void delete(DocumentWorkspace workspace) {
        throw new IllegalStateException("Document Workspace byte deletion is unavailable");
    }
}
