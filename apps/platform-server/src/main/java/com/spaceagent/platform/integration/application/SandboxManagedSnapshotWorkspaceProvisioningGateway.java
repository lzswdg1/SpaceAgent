package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SandboxManagedSnapshotWorkspaceProvisioningGateway
        implements ManagedSnapshotWorkspaceProvisioningGateway {
    private final SandboxComputeApplicationApi sandbox; private final ObjectMapper json; private final Path root;
    public SandboxManagedSnapshotWorkspaceProvisioningGateway(SandboxComputeApplicationApi sandbox,
            ObjectMapper json, WorkspaceProperties properties) {
        this.sandbox=sandbox;this.json=json;this.root=Path.of(properties.getManagedRoot()).toAbsolutePath().normalize();
    }
    @Override public WorkspaceProvisioningGateway.ProvisionedWorkspace provision(Workspace workspace,SourceRepository source) {
        if(source.type()!=SourceRepositoryType.MANAGED_SNAPSHOT||source.state()!=SourceRepositoryState.READY)
            throw new IllegalArgumentException("Managed snapshot Source is not READY");
        Path target=root.resolve("workspaces").resolve(workspace.id()).normalize();
        if(!target.startsWith(root.resolve("workspaces").normalize()))throw new IllegalArgumentException("Workspace path escaped root");
        try{Files.createDirectories(target);var result=sandbox.execute(new SandboxComputeApplicationApi.ComputeCommand(
                "snapshot-materialize:"+workspace.id(),"snapshot-materialize:"+workspace.id(),
                "workspaces/"+workspace.id(),workspace.taskId(),"managed-snapshot-materialize",
                "spaceagent-workspace-tool",List.of("snapshot-materialize"),120,null,source.snapshotRef()));
            if(!"SUCCEEDED".equals(result.status())||result.exitStatus()!=0)throw new IllegalStateException("Sandbox snapshot materialization failed");
            var node=json.readTree(result.stdout());String head=node.path("headCommit").asText();
            if(!head.matches("[0-9a-f]{40,64}"))throw new IllegalStateException("Sandbox snapshot evidence invalid");
            return new WorkspaceProvisioningGateway.ProvisionedWorkspace("managed:"+workspace.id(),head);
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Sandbox snapshot materialization failed",e);}
    }
}
