package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.integration.application.SandboxWorkspaceGateway;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxWorkspaceGatewayTest {
    @Test void fileMutationUsesBoundedInputAndImmutableOciHelper(){
        var sandbox=mock(SandboxComputeApplicationApi.class);
        when(sandbox.execute(any())).thenReturn(new SandboxComputeApplicationApi.ComputeResult(
                "SUCCEEDED",0,"{\"path\":\"src/a.txt\",\"sizeBytes\":4,\"changedFiles\":[\"src/a.txt\"]}","",false,1,80,null));
        var gateway=new SandboxWorkspaceGateway(sandbox,new ObjectMapper());
        var result=gateway.execute(workspace(),new WorkspaceSandboxGateway.Execution("run","call"),
                new WorkspaceCodingGateway.CodingOperation(WorkspaceCodingGateway.Type.WRITE_FILE,
                        "src/a.txt","done",null,List.of(),30));
        ArgumentCaptor<SandboxComputeApplicationApi.ComputeCommand> capture=ArgumentCaptor.forClass(SandboxComputeApplicationApi.ComputeCommand.class);
        verify(sandbox).execute(capture.capture());var command=capture.getValue();
        assertThat(command.executable()).isEqualTo("spaceagent-workspace-tool");
        assertThat(command.tool()).isEqualTo("workspace-write-file");
        assertThat(new String(Base64.getDecoder().decode(command.inputBase64()),StandardCharsets.UTF_8)).isEqualTo("done");
        assertThat(result.changedFiles()).containsExactly("src/a.txt");
    }
    @Test void fileReadUsesReadOnlyClassifiedOperation(){
        var sandbox=mock(SandboxComputeApplicationApi.class);
        when(sandbox.execute(any())).thenReturn(new SandboxComputeApplicationApi.ComputeResult(
                "SUCCEEDED",0,"{\"path\":\"README.md\",\"content\":\"hello\",\"sizeBytes\":5,\"truncated\":false}","",false,1,90,null));
        var result=new SandboxWorkspaceGateway(sandbox,new ObjectMapper()).readFile(workspace(),
                new WorkspaceSandboxGateway.Execution("run","call"),"README.md",1000);
        assertThat(result.content()).isEqualTo("hello");
        ArgumentCaptor<SandboxComputeApplicationApi.ComputeCommand> capture=ArgumentCaptor.forClass(SandboxComputeApplicationApi.ComputeCommand.class);
        verify(sandbox).execute(capture.capture());assertThat(capture.getValue().tool()).startsWith("workspace-read-");
    }
    @Test void preparedCommitCarriesExactBoundedBundleEvidence(){
        var sandbox=mock(SandboxComputeApplicationApi.class);
        String commit="a".repeat(40);
        String reference=".git/spaceagent-transfer/"+commit+".bundle";
        byte[] bundle="bundle-data".getBytes(StandardCharsets.UTF_8);
        String hash=sha256(bundle);
        when(sandbox.execute(any())).thenReturn(
                new SandboxComputeApplicationApi.ComputeResult(
                        "SUCCEEDED",0,"{\"commit\":\""+commit+"\",\"bundleReference\":\""+reference
                                +"\",\"bundleSha256\":\""+hash+"\",\"bundleBytes\":"+bundle.length+"}","",false,1,180,null),
                new SandboxComputeApplicationApi.ComputeResult(
                        "SUCCEEDED",0,"{\"commit\":\""+commit+"\",\"bundleReference\":\""+reference
                                +"\",\"offset\":0,\"totalBytes\":"+bundle.length+",\"chunkBase64\":\""
                                +Base64.getEncoder().encodeToString(bundle)+"\"}","",false,1,180,null));
        var gateway=new SandboxWorkspaceGateway(sandbox,new ObjectMapper());
        var prepared=gateway.prepareCommit(workspace(),new WorkspaceSandboxGateway.Execution("run","call"),
                "c".repeat(40),"sha256:"+"d".repeat(64),"reviewed");
        assertThat(prepared.commit()).isEqualTo(commit);
        assertThat(prepared.bundleReference()).isEqualTo(reference);
        assertThat(prepared.bundleSha256()).isEqualTo(hash);
        assertThat(prepared.bundleSizeBytes()).isEqualTo(bundle.length);
        assertThat(prepared.bundleBytes()).isEqualTo(bundle);
        byte[] returned=prepared.bundleBytes();returned[0]=0;
        assertThat(prepared.bundleBytes()).isEqualTo(bundle);
    }
    @Test void malformedPreparedBundleEvidenceFailsClosed(){
        var sandbox=mock(SandboxComputeApplicationApi.class);
        when(sandbox.execute(any())).thenReturn(new SandboxComputeApplicationApi.ComputeResult(
                "SUCCEEDED",0,"{\"commit\":\""+"a".repeat(40)
                        +"\",\"bundleReference\":\".git/config\",\"bundleSha256\":\"sha256:"
                        +"b".repeat(64)+"\",\"bundleBytes\":1}","",false,1,180,null));
        assertThatThrownBy(()->new SandboxWorkspaceGateway(sandbox,new ObjectMapper()).prepareCommit(
                workspace(),new WorkspaceSandboxGateway.Execution("run","call"),"c".repeat(40),
                "sha256:"+"d".repeat(64),"reviewed"))
                .isInstanceOf(com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException.class);
    }
    @Test void tamperedPreparedBundleChunkFailsClosed(){
        var sandbox=mock(SandboxComputeApplicationApi.class);
        String commit="a".repeat(40),reference=".git/spaceagent-transfer/"+"a".repeat(40)+".bundle";
        byte[] expected="expected".getBytes(StandardCharsets.UTF_8);
        byte[] tampered="tampered".getBytes(StandardCharsets.UTF_8);
        when(sandbox.execute(any())).thenReturn(
                new SandboxComputeApplicationApi.ComputeResult("SUCCEEDED",0,
                        "{\"commit\":\""+commit+"\",\"bundleReference\":\""+reference
                                +"\",\"bundleSha256\":\""+sha256(expected)
                                +"\",\"bundleBytes\":"+expected.length+"}","",false,1,180,null),
                new SandboxComputeApplicationApi.ComputeResult("SUCCEEDED",0,
                        "{\"commit\":\""+commit+"\",\"bundleReference\":\""+reference
                                +"\",\"offset\":0,\"totalBytes\":"+expected.length
                                +",\"chunkBase64\":\""+Base64.getEncoder().encodeToString(tampered)
                                +"\"}","",false,1,180,null));
        assertThatThrownBy(()->new SandboxWorkspaceGateway(sandbox,new ObjectMapper()).prepareCommit(
                workspace(),new WorkspaceSandboxGateway.Execution("run","call"),"c".repeat(40),
                "sha256:"+"d".repeat(64),"reviewed"))
                .isInstanceOf(com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException.class);
    }
    private static String sha256(byte[] value){try{return "sha256:"+java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception error){throw new AssertionError(error);}}
    private static Workspace workspace(){Instant now=Instant.now();return new Workspace(
            UUID.randomUUID().toString(),"tenant",UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,"primary", WorkspaceMode.MANAGED_GIT,
            UUID.randomUUID().toString(),"main","branch","managed", "a".repeat(40),true,
            WorkspaceState.READY,null,1,"user",now,now);}
}
