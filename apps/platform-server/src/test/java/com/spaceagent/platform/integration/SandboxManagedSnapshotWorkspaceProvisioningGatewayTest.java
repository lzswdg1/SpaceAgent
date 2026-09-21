package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.integration.application.SandboxManagedSnapshotWorkspaceProvisioningGateway;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SandboxManagedSnapshotWorkspaceProvisioningGatewayTest {
    @TempDir Path temp;
    @Test void sendsExactReadOnlySourceAndWorkspaceRefsToSandbox() {
        var sandbox=mock(SandboxComputeApplicationApi.class);
        when(sandbox.execute(any())).thenReturn(new SandboxComputeApplicationApi.ComputeResult(
                "SUCCEEDED",0,"{\"headCommit\":\""+"a".repeat(40)+"\",\"fileCount\":1}","",false,1,80,null));
        WorkspaceProperties properties=new WorkspaceProperties();properties.setManagedRoot(temp.resolve("managed").toString());
        var gateway=new SandboxManagedSnapshotWorkspaceProvisioningGateway(sandbox,new ObjectMapper(),properties);
        Instant now=Instant.EPOCH;String session="00000000-0000-4000-8000-000000000010";
        var source=new SourceRepository("00000000-0000-4000-8000-000000000011","00000000-0000-4000-8000-000000000012",
                "tenant",null,null,null,"snapshot:"+session,"Snapshot",null,null,"snapshot",
                SourceRepositoryType.MANAGED_SNAPSHOT,SourceRepositoryState.READY,SourceRepositoryVisibility.PRIVATE,
                "owner",now,now,session,"sources/"+session,"sha256:"+"b".repeat(64),"sha256:"+"b".repeat(64),"finalize");
        var workspace=new Workspace("00000000-0000-4000-8000-000000000013","tenant",source.projectId(),
                "00000000-0000-4000-8000-000000000014","00000000-0000-4000-8000-000000000015",source.id(),null,
                "primary",WorkspaceMode.MANAGED_GIT,"00000000-0000-4000-8000-000000000016","snapshot",
                "spaceagent/test",null,null,true,WorkspaceState.PROVISIONING,null,0,"owner",now,now);
        assertThat(gateway.provision(workspace,source).headCommit()).isEqualTo("a".repeat(40));
        var captor=ArgumentCaptor.forClass(SandboxComputeApplicationApi.ComputeCommand.class);verify(sandbox).execute(captor.capture());
        assertThat(captor.getValue().workspaceRef()).isEqualTo("workspaces/"+workspace.id());
        assertThat(captor.getValue().sourceRef()).isEqualTo(source.snapshotRef());
        assertThat(captor.getValue().tool()).isEqualTo("managed-snapshot-materialize");
        assertThat(temp.resolve("managed/workspaces").resolve(workspace.id())).isDirectory();
    }
}
