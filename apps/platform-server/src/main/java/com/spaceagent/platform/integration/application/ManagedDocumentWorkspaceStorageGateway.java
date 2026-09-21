package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import com.spaceagent.platform.knowledge.domain.DocumentWorkspaceStorageGateway;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import org.springframework.stereotype.Component;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionCommand;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates only the empty server-managed mount namespace; byte access remains OCI-only. */
@Component
public class ManagedDocumentWorkspaceStorageGateway implements DocumentWorkspaceStorageGateway {
    private final Path root;
    private final SandboxToolExecutionApplicationApi sandbox;
    public ManagedDocumentWorkspaceStorageGateway(WorkspaceProperties properties,SandboxToolExecutionApplicationApi sandbox){
        root=Path.of(properties.getManagedRoot()).toAbsolutePath().normalize().resolve("document-workspaces");
        this.sandbox=sandbox;
    }
    @Override public void provision(DocumentWorkspace workspace){
        Path target=root.resolve(workspace.id()).normalize();
        if(!target.startsWith(root))throw new IllegalArgumentException("Document Workspace namespace escaped root");
        try{Files.createDirectories(target);}catch(Exception error){throw new IllegalStateException("Document Workspace namespace provisioning failed",error);}
    }
    @Override public void delete(DocumentWorkspace workspace){
        var result=sandbox.execute(new SandboxToolExecutionCommand("knowledge-cleanup:"+workspace.id(),
                "knowledge-cleanup:"+workspace.id(),"document-workspace-delete-all",
                "delete-all:"+workspace.id(),"delete-all:"+workspace.id(),"spaceagent-workspace-tool",
                java.util.List.of("document-file-clear"),"document-workspaces/"+workspace.id(),
                "document-workspace:"+workspace.id(),120));
        if(!"SUCCEEDED".equals(result.status()))throw new IllegalStateException("Document Workspace bytes deletion failed");
        try{Files.deleteIfExists(root.resolve(workspace.id()).normalize());}catch(Exception error){throw new IllegalStateException("Document Workspace namespace deletion failed",error);}
    }
}
