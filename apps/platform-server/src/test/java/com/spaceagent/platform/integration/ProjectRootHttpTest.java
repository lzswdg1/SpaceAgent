package com.spaceagent.platform.integration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProjectRootHttpTest.WorkspaceFixture.class)
class ProjectRootHttpTest {
    static final Path STORAGE;
    static {try{STORAGE=Files.createTempDirectory("spaceagent-root-test-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("platform.workspace.managed-root",()->STORAGE.toString());}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Test void emptyRootOwnsPhysicalStorageAndLogicalDeletionKeepsCodeAndConversation() throws Exception {
        String token=register();
        var root=postData(token,"/api/v1/project-roots",Map.of("name","My blank folder"));
        String id=root.path("id").asText(),project=root.path("projectId").asText(),source=root.path("sourceRepositoryId").asText();
        assertThat(root.path("storageState").asText()).isEqualTo("READY");
        Path mirror=STORAGE.resolve("repositories").resolve(source+".git");
        assertThat(mirror).isDirectory();
        assertThat(mirror.resolve("refs/heads/main")).exists();
        Path internalRef=mirror.resolve("refs/heads/spaceagent/legacy");
        Files.createDirectories(internalRef.getParent());
        Files.copy(mirror.resolve("refs/heads/main"),internalRef);
        String branchJson=mvc.perform(get("/api/v1/projects/"+project+"/sources/"+source+"/branches").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(branchJson).contains("main").doesNotContain("spaceagent/");
        assertThat(internalRef).exists();
        var again=postData(token,"/api/v1/project-roots/"+id+"/prepare",Map.of());
        assertThat(again.path("sourceRepositoryId").asText()).isEqualTo(source);
        String other=register();
        mvc.perform(delete("/api/v1/project-roots/"+id).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        String agent=postData(token,"/api/v1/agents",Map.of("name","Root Agent")).path("id").asText();
        String conversation=postData(token,"/api/v1/chat/conversations",Map.of("agentId",agent,"projectId",project,"projectDirectoryId",id,"name","Root chat")).path("conversationId").asText();
        String task=postData(token,"/api/v1/projects/"+project+"/tasks",Map.of("title","Root task","goal","Keep code")).path("id").asText();
        String workspace=postData(token,"/api/v1/projects/"+project+"/workspaces",Map.of("taskId",task,"sourceRepositoryId",source,"projectDirectoryId",id,"baseRef","main")).path("id").asText();
        Path workspaceRoot=STORAGE.resolve("workspaces").resolve(workspace);
        Path hostMarker=STORAGE.resolve("host-fsmonitor-marker");
        Path fsmonitor=workspaceRoot.resolve("malicious-fsmonitor.sh");
        Files.writeString(fsmonitor,"#!/bin/sh\ntouch '"+hostMarker+"'\n",StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(fsmonitor,PosixFilePermissions.fromString("rwx------"));
        Files.writeString(workspaceRoot.resolve(".git/config"),
                "\n[core]\n\tfsmonitor = "+fsmonitor+"\n",StandardOpenOption.APPEND);
        String workspaceJson=mvc.perform(get("/api/v1/projects/"+project+"/workspaces").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(workspaceJson).doesNotContain("spaceagent/");
        String environment=mvc.perform(get("/api/v1/projects/"+project+"/workspaces/"+workspace+"/environment").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(environment).doesNotContain("spaceagent/");
        assertThat(json.readTree(environment).path("data").path("branch").asText()).isEqualTo("main");
        assertThat(hostMarker).doesNotExist();
        Path code=STORAGE.resolve("workspaces").resolve(workspace).resolve("keep.txt");
        Files.writeString(code,"retained code");
        mvc.perform(delete("/api/v1/projects/"+project+"/workspaces/"+workspace).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(code).hasContent("retained code");
        mvc.perform(delete("/api/v1/project-roots/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(mirror).exists();
        assertThat(code).hasContent("retained code");
        mvc.perform(get("/api/v1/chat/conversations/"+conversation).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/chat/messages").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("agentId",agent,"conversationId",conversation,"message","hello")))).andExpect(status().isConflict());
        var roots=mvc.perform(get("/api/v1/project-roots").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        assertThat(roots.getResponse().getContentAsString()).doesNotContain(id);
        mvc.perform(post("/api/v1/project-roots/"+id+"/prepare").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    private String register()throws Exception{return postData(null,"/api/v1/auth/register",Map.of("username",UUID.randomUUID()+"@example.com","password","password123","displayName","Root owner")).path("token").asText();}
    private JsonNode postData(String token,String path,Object body)throws Exception{
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        var response=mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    }

    @TestConfiguration
    static class WorkspaceFixture {
        @Bean
        @Primary
        WorkspaceSandboxGateway workspaceSandboxGateway() {
            WorkspaceSandboxGateway gateway=mock(WorkspaceSandboxGateway.class);
            when(gateway.snapshot(any(),any(),anyInt())).thenAnswer(invocation->{
                Workspace workspace=invocation.getArgument(0);
                return new WorkspaceSandboxGateway.Snapshot(
                        workspace.headCommit(),"","",List.of(),false);
            });
            return gateway;
        }
    }
}
