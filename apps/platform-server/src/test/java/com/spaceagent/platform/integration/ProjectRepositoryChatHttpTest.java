package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.platform.runtime.application.ProjectChatWorkspaceBinding;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"platform.sandbox.mode=http", "platform.sandbox.base-url=http://127.0.0.1:9200",
        "platform.sandbox.internal-token=repository-chat-fixture-token-12345678"})
@AutoConfigureMockMvc
@Import(ProjectRepositoryChatHttpTest.Fixtures.class)
class ProjectRepositoryChatHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectApplicationApi projects;
    @Autowired TaskApplicationApi tasks;
    @Autowired ProjectDirectoryApplicationApi directories;
    @Autowired SourceRepositoryRepository sources;
    @Autowired WorkspaceRepository workspaces;
    @Autowired RuntimeApplicationApi runtime;
    @Autowired ChatRuntimeApplicationApi chatRuntime;
    @Autowired RuntimeToolExecutionApplicationApi tools;
    @Autowired ToolExecutionLedgerApplicationApi ledger;
    @Autowired ModelCallLedgerRepository modelLedger;
    @Autowired ProjectChatWorkspaceBinding binding;
    @Autowired ChatRunLifecycleApi lifecycle;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.spaceagent.platform.memory.api.MemoryApplicationApi memoryApi;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi knowledgeApi;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.spaceagent.platform.inference.api.ModelPoolApplicationApi pools;
    @Autowired com.spaceagent.platform.inference.api.InferenceApplicationApi modelCatalog;
    @Autowired com.spaceagent.platform.agent.api.AgentApplicationApi agents;

    @Test void streamsListReadAndAnswerAndRetainsBindingAfterHistoryRefresh() throws Exception {
        var f = fixture();
        MvcResult pending = mvc.perform(post("/api/v1/chat/messages/stream")
                .header("Authorization", "Bearer " + f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId", f.conversation,
                        "agentId", f.agent, "workspaceId", f.workspace.id(), "message", "Read README"))))
                .andExpect(status().isOk()).andReturn();
        String stream = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(stream).contains("event:done", "README says repository fixture", "event:delta")
                .doesNotContain("event:error", "I will read key files again");
        String done = stream.substring(stream.indexOf("event:done") + "event:done".length()).trim().substring(5).trim();
        String runId = json.readTree(done).path("agentRunId").asText();
        var run = runtime.findRun(runId).orElseThrow();
        assertThat(binding.find(run).orElseThrow().workspaceId()).isEqualTo(f.workspace.id());
        assertThat(ledger.findByRunId(runId)).hasSize(2);
        String history = mvc.perform(get("/api/v1/chat/conversations/" + f.conversation + "/messages")
                .header("Authorization", "Bearer " + f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("README says repository fixture");
        assertThatThrownBy(() -> tools.execute(new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(
                f.user, runId, "step", "forbidden-write", "document_write",
                json.writeValueAsString(Map.of("workspaceId", f.workspace.id(), "path", "README.md", "content", "changed", "format", "markdown")))))
                .hasMessageContaining("Repository chat requires");
        assertThatThrownBy(() -> binding.readScope(run, f.user, "file_read", UUID.randomUUID().toString()))
                .hasMessageContaining("Repository chat requires");
        workspaces.save(f.workspace.archive(Instant.now()));
        assertThatThrownBy(() -> binding.readScope(run, f.user, "file_read", f.workspace.id()))
                .hasMessageContaining("Repository chat requires");
    }

    @Test void repairsTextualInvocationOnceAndPublishesOnlyTheActualAnswer() throws Exception {
        var f=fixture();
        var pending=mvc.perform(post("/api/v1/chat/messages/stream")
                .header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId",f.conversation,"agentId",f.agent,
                        "workspaceId",f.workspace.id(),"message","protocol-fixture"))))
                .andExpect(status().isOk()).andReturn();
        String stream=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("event:done","README says repository fixture","tool_protocol_correction")
                .doesNotContain("event:error","<invoke","I will read more files");
        var state=lifecycle.latest(f.tenant,f.user,f.conversation);
        assertThat(state.executionState()).isEqualTo("COMPLETED");
        assertThat(ledger.findByRunId(state.agentRunId())).hasSize(2);
        var calls=modelLedger.findByRunId(state.agentRunId());
        assertThat(calls).hasSize(4);
        assertThat(calls.stream().filter(call->call.logicalCallId().endsWith(":protocol-correction")).count()).isEqualTo(1);
        String history=mvc.perform(get("/api/v1/chat/conversations/"+f.conversation+"/messages")
                .header("Authorization","Bearer "+f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("README says repository fixture").doesNotContain("<invoke","I will read more files");
    }

    @Test void repeatedMalformedProtocolFailsInsteadOfCompletingOrExecutingText() throws Exception {
        var f=fixture();
        var pending=mvc.perform(post("/api/v1/chat/messages/stream")
                .header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId",f.conversation,"agentId",f.agent,
                        "workspaceId",f.workspace.id(),"message","protocol-fixture always-invalid"))))
                .andExpect(status().isOk()).andReturn();
        String stream=mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("event:error","CHAT_TOOL_PROTOCOL_INVALID").doesNotContain("event:done","<invoke");
        var state=lifecycle.latest(f.tenant,f.user,f.conversation);
        assertThat(state.executionState()).isEqualTo("FAILED");
        assertThat(ledger.findByRunId(state.agentRunId())).isEmpty();
        assertThat(modelLedger.findByRunId(state.agentRunId())).hasSize(2);
    }

    @Test void correctionCannotReenableToolsDuringFinalization() throws Exception {
        var f=fixture();
        mvc.perform(patch("/api/v1/agents/"+f.agent).header("Authorization","Bearer "+f.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxTurns\":1}")).andExpect(status().isOk());
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,
                "agentId",f.agent,"workspaceId",f.workspace.id(),"message","protocol-fixture tool-free"));
        assertThat(result.path("assistantMessage").asText()).isEqualTo("Only directory evidence is available; file contents were not inspected.");
        assertThat(result.path("inputTokenCount").asInt()).isEqualTo(150);
        assertThat(result.path("outputTokenCount").asInt()).isEqualTo(20);
        assertThat(ledger.findByRunId(result.path("agentRunId").asText())).hasSize(1);
    }

    @Test void correctionWithUnknownProviderResultDoesNotReplayToolsOrRetryAgain() throws Exception {
        var f=fixture();
        mvc.perform(post("/api/v1/chat/messages").header("Authorization","Bearer "+f.token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "conversationId",f.conversation,"agentId",f.agent,"workspaceId",f.workspace.id(),
                        "message","protocol-fixture correction-unknown")))).andExpect(status().is5xxServerError());
        var state=lifecycle.latest(f.tenant,f.user,f.conversation);
        var calls=modelLedger.findByRunId(state.agentRunId());
        assertThat(calls).hasSize(3);
        assertThat(calls.stream().filter(call->call.status()==ModelCallStatus.UNKNOWN).count()).isEqualTo(1);
        assertThat(ledger.findByRunId(state.agentRunId())).hasSize(1);
        assertThat(state.executionState()).isNotEqualTo("COMPLETED");
    }

    @Test void fencedXmlDocumentationIsAnAnswerNotAProtocolError() throws Exception {
        var f=fixture();
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,
                "agentId",f.agent,"workspaceId",f.workspace.id(),"message","code-example-fixture"));
        assertThat(result.path("assistantMessage").asText()).contains("```xml", "<invoke");
        assertThat(modelLedger.findByRunId(result.path("agentRunId").asText())).hasSize(1);
        assertThat(ledger.findByRunId(result.path("agentRunId").asText())).isEmpty();
    }

    @Test void rejectsWrongDirectoryCrossTenantAndArchivedDirectoryBeforeInference() throws Exception {
        var f = fixture();
        String defaultConversation = call(f.token, "/api/v1/chat/conversations", Map.of("projectId", f.project,
                "agentId", f.agent, "name", "default")).path("conversationId").asText();
        mvc.perform(post("/api/v1/chat/messages").header("Authorization", "Bearer " + f.token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("conversationId", defaultConversation,
                        "agentId", f.agent, "workspaceId", f.workspace.id(), "message", "read"))))
                .andExpect(status().isConflict());
        var other = fixture();
        mvc.perform(post("/api/v1/chat/messages").header("Authorization", "Bearer " + other.token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("conversationId", other.conversation,
                        "agentId", other.agent, "workspaceId", f.workspace.id(), "message", "read"))))
                .andExpect(status().isNotFound());
        directories.archive(new ProjectDirectoryApplicationApi.ArchiveCommand(f.tenant, f.user, f.project, f.workspace.projectDirectoryId()));
        mvc.perform(post("/api/v1/chat/messages").header("Authorization", "Bearer " + f.token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("conversationId", f.conversation,
                        "agentId", f.agent, "workspaceId", f.workspace.id(), "message", "read"))))
                .andExpect(status().isConflict());
    }

    @Test void repeatedReadUsesPriorResultAndTransitionsToAnswerWithoutAnotherToolCall() throws Exception {
        var f=fixture();
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,
                "agentId",f.agent,"workspaceId",f.workspace.id(),"message","repeat-fixture"));
        assertThat(result.path("assistantMessage").asText()).contains("README says repository fixture");
        assertThat(ledger.findByRunId(result.path("agentRunId").asText())).hasSize(2);
    }

    @Test void lengthLimitedResponseContinuesAndPersistsTheWholeAnswer() throws Exception {
        var f=fixture();
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,
                "agentId",f.agent,"workspaceId",f.workspace.id(),"message","continuation-fixture"));
        assertThat(result.path("assistantMessage").asText()).isEqualTo("Partial answer. Completed answer.");
        assertThat(result.path("inputTokenCount").asInt()).isEqualTo(100);
        assertThat(result.path("outputTokenCount").asInt()).isEqualTo(16);
        assertThat(ledger.findByRunId(result.path("agentRunId").asText())).isEmpty();
    }

    @Test void repeatedLengthLimitStopsWithAnExplicitIncompleteNotice() throws Exception {
        var f=fixture();
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,
                "agentId",f.agent,"workspaceId",f.workspace.id(),"message","continuation-fixture always-length"));
        assertThat(result.path("assistantMessage").asText()).contains("answer is incomplete", "Ask to continue");
        assertThat(result.path("assistantMessage").asText().split("Partial answer",-1)).hasSize(4);
    }

    @Test void streamingContinuationMatchesPersistedHistory() throws Exception {
        var f=fixture();
        MvcResult pending=mvc.perform(post("/api/v1/chat/messages/stream")
                .header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId",f.conversation,"agentId",f.agent,
                        "workspaceId",f.workspace.id(),"message","continuation-fixture"))))
                .andExpect(status().isOk()).andReturn();
        String stream=mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains("event:done","Partial answer.","Completed answer.").doesNotContain("event:error");
        String history=mvc.perform(get("/api/v1/chat/conversations/"+f.conversation+"/messages")
                .header("Authorization","Bearer "+f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("Partial answer. Completed answer.");
    }

    @Test void failedContinuationKeepsPartialTextAndExposesItsState()throws Exception{
        var f=fixture();
        mvc.perform(post("/api/v1/chat/messages").header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId",f.conversation,"agentId",f.agent,"workspaceId",f.workspace.id(),
                        "message","continuation-fixture fail-next")))).andExpect(status().is5xxServerError());
        String history=mvc.perform(get("/api/v1/chat/conversations/"+f.conversation+"/messages/page")
                .header("Authorization","Bearer "+f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("Partial answer.","ASSISTANT_PARTIAL");
        var state=lifecycle.latest(f.tenant,f.user,f.conversation);
        assertThat(state.executionState()).isEqualTo("FAILED");assertThat(state.partial()).isTrue();
        mvc.perform(get("/api/v1/chat/runs/"+state.agentRunId()+"/status").header("Authorization","Bearer "+fixture().token))
                .andExpect(status().isNotFound());
    }

    @Test void ordinaryChatAlsoContinuesLengthLimitedAnswers()throws Exception{
        var f=fixture();
        String conversation=call(f.token,"/api/v1/chat/conversations",Map.of("agentId",f.agent,"name","ordinary")).path("conversationId").asText();
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",conversation,"agentId",f.agent,"message","continuation-fixture"));
        assertThat(result.path("assistantMessage").asText()).isEqualTo("Partial answer. Completed answer.");
    }

    @Test void modelSelectionFailureLeavesATerminalRunRatherThanAnOrphanInProgress()throws Exception{
        var f=fixture();var agent=agents.findById(f.agent).orElseThrow();
        call(f.token,"/api/v1/model-providers/"+agent.modelProviderId()+"/test",Map.of());
        var pool=pools.createPool(new com.spaceagent.platform.inference.api.CreateModelPoolCommand(f.tenant,f.user,"pool-"+UUID.randomUUID(),ModelPoolVisibility.PRIVATE,ModelPoolRoutingStrategy.PRIORITY,false));
        pools.addMember(new com.spaceagent.platform.inference.api.AddModelPoolMemberCommand(f.tenant,f.user,pool.id(),agent.modelProviderId(),modelCatalog.listModels(agent.modelProviderId()).getFirst().id(),0,100));
        pools.activatePool(new com.spaceagent.platform.inference.api.UpdateModelPoolStatusCommand(f.tenant,f.user,pool.id()));
        mvc.perform(patch("/api/v1/agents/"+f.agent).header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("modelPoolId",pool.id(),"modelProviderId","","modelId",""))))
                .andExpect(status().isOk());
        doThrow(new com.spaceagent.shared.exception.BusinessException("fixture routing failure",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"FIXTURE_ROUTING"))
                .when(pools).resolvePool(eq(f.tenant),eq(f.user),eq(pool.id()),argThat(key->key!=null&&!key.equals(pool.id())));
        mvc.perform(post("/api/v1/chat/messages").header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("conversationId",f.conversation,"agentId",f.agent,"workspaceId",f.workspace.id(),"message","route failure"))))
                .andExpect(status().isServiceUnavailable());
        assertThat(lifecycle.latest(f.tenant,f.user,f.conversation).executionState()).isEqualTo("FAILED");
    }

    @Test void disabledMemoryAndRagDoNotInvokeTheirOwnersEvenWithKnowledgeBound()throws Exception{
        var f=fixture();
        var document=knowledgeApi.createDocument(new com.spaceagent.platform.knowledge.api.CreateKnowledgeDocumentCommand(f.user,"bound","text/plain","fixture:bound"));
        mvc.perform(patch("/api/v1/agents/"+f.agent).header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("memoryEnabled",false,"ragEnabled",false,"knowledgeBaseIds",List.of(document.id())))))
                .andExpect(status().isOk());
        var result=call(f.token,"/api/v1/chat/messages",Map.of("conversationId",f.conversation,"agentId",f.agent,"workspaceId",f.workspace.id(),"message","remember I prefer concise architecture reviews"));
        assertThat(result.path("memoryUpdated").asBoolean()).isFalse();assertThat(result.path("ragUsed").asBoolean()).isFalse();
        verify(memoryApi,never()).recall(argThat(c->c!=null&&f.user.equals(c.scope().scopeId())));
        verify(memoryApi,never()).evaluateMessage(argThat(c->c!=null&&f.user.equals(c.userId())));
        verify(knowledgeApi,never()).retrieve(argThat(c->c!=null&&f.user.equals(c.ownerId())));
    }

    @Test void cancellationStopsTheRunWithoutExecutingReturnedToolsAndKeepsUnknownUsage()throws Exception{
        var f=fixture();var slow=new Fixtures.SlowCall();Fixtures.slowCalls.put(f.workspace.id(),slow);
        var running=java.util.concurrent.CompletableFuture.supplyAsync(()->chatRuntime.executeStreaming(new ChatExecutionCommand(
                f.tenant,f.user,f.conversation,f.agent,"cancel-fixture",null,List.of(),f.workspace.id()),new ChatRuntimeApplicationApi.ChatStreamObserver(){
                    public void onRuntimeEvent(ChatRuntimeEvent event){} public void onContentDelta(String text){} public void onReasoningDelta(String text){}
                }));
        try{
            assertThat(slow.started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var run=lifecycle.latest(f.tenant,f.user,f.conversation);
            assertThat(lifecycle.cancel(f.tenant,f.user,run.agentRunId()).executionState()).isEqualTo("CANCELLING");
            slow.release.countDown();
            assertThatThrownBy(()->running.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.spaceagent.shared.exception.BusinessException.class);
            var stopped=lifecycle.get(f.tenant,f.user,run.agentRunId());
            assertThat(stopped.executionState()).isEqualTo("CANCELLED");assertThat(stopped.uncertainCalls()).isGreaterThan(0);
            assertThat(ledger.findByRunId(run.agentRunId())).isEmpty();
        }finally{slow.release.countDown();Fixtures.slowCalls.remove(f.workspace.id());}
    }

    @Test void rejectsTerminalTaskWithoutAppendingMessages() throws Exception {
        var f = fixture();
        tasks.transitionTask(new TransitionTaskCommand(f.tenant, f.user, f.project, f.workspace.taskId(), TaskTransition.CANCEL));
        mvc.perform(post("/api/v1/chat/messages").header("Authorization", "Bearer " + f.token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("conversationId", f.conversation,
                        "agentId", f.agent, "workspaceId", f.workspace.id(), "message", "read"))))
                .andExpect(status().isConflict());
        var history = mvc.perform(get("/api/v1/chat/conversations/" + f.conversation + "/messages")
                .header("Authorization", "Bearer " + f.token)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(history.getResponse().getContentAsByteArray()).path("data")).isEmpty();
    }

    @Test void repositoryBrowserUsesAuthorizedWorkspaceAndRejectsMetadataAndTraversal() throws Exception {
        var f = fixture();
        String path="/api/v1/projects/"+f.project+"/workspaces/"+f.workspace.id();
        var file=mvc.perform(get(path+"/file").param("path","README.md").header("Authorization","Bearer "+f.token))
                .andExpect(status().isOk()).andReturn();
        assertThat(file.getResponse().getContentAsString()).contains("repository fixture");
        for(String invalid : List.of("../README.md", ".git/config", "/etc/passwd")) {
            mvc.perform(get(path+"/file").param("path",invalid).header("Authorization","Bearer "+f.token))
                    .andExpect(status().isBadRequest());
        }
        var other=fixture();
        mvc.perform(get(path+"/file").param("path","README.md").header("Authorization","Bearer "+other.token))
                .andExpect(status().isNotFound());
    }

    private Fixture fixture() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        JsonNode auth = call(null, "/api/v1/auth/register", Map.of("username", email, "password", "password123", "displayName", "Reader"));
        String token = auth.path("token").asText(), user = auth.path("userId").asText(), tenant = auth.path("tenantId").asText();
        String provider = call(token, "/api/v1/model-providers", Map.of("name", "fixture", "type", "openai",
                "baseUrl", "https://example.com/v1", "apiKey", "fixture", "models", List.of(Map.of(
                        "modelId", "reader", "displayName", "reader", "maxContextTokens", 200000, "isDefault", true)))).path("id").asText();
        String agent = call(token, "/api/v1/agents", Map.of("name", "Reader", "modelProviderId", provider, "modelId", "reader",
                "maxTurns",8,"enabledToolIds", List.of("file_list", "file_read", "document_write"))).path("id").asText();
        String project = projects.createProject(new CreateProjectCommand(tenant, user, "repo", null)).id();
        String task = tasks.createTask(new CreateTaskCommand(tenant, user, project, null, "Read", "Read", null, List.of(), List.of())).id();
        Instant now = Instant.now();
        String sourceId = UUID.randomUUID().toString();
        sources.save(new SourceRepository(sourceId, project, tenant, null, null, null, "fixture/repo", "fixture/repo",
                "https://example.com/repo.git", null, "main", SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PUBLIC, user, now, now));
        var directory = directories.create(new ProjectDirectoryApplicationApi.CreateCommand(tenant, user, project, sourceId, "fixture/repo", "."));
        var workspace = new Workspace(UUID.randomUUID().toString(), tenant, project, directory.id(), task, sourceId,
                null, "primary", WorkspaceMode.MANAGED_GIT, UUID.randomUUID().toString(), "main", "fixture/read", "fixture", "a".repeat(40),
                true, WorkspaceState.READY, null, 0, user, now, now);
        workspaces.save(workspace);
        String conversation = call(token, "/api/v1/chat/conversations", Map.of("projectId", project,
                "projectDirectoryId", directory.id(), "activeTaskId", task, "agentId", agent, "name", "Read repo"))
                .path("conversationId").asText();
        return new Fixture(token, user, tenant, project, agent, conversation, workspace);
    }
    private JsonNode call(String token, String path, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        var result = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
    record Fixture(String token, String user, String tenant, String project, String agent, String conversation, Workspace workspace) {}

    @TestConfiguration static class Fixtures {
        static class SlowCall { final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1); }
        static final Map<String,SlowCall> slowCalls=new java.util.concurrent.ConcurrentHashMap<>();
        @Bean @Primary ModelProviderConnectionTester connection() { return p -> new ProviderConnectionProbeResult(true, 1, List.of("reader"), null); }
        @Bean @Primary WorkspaceSandboxGateway sandbox() {
            var gateway = mock(WorkspaceSandboxGateway.class);
            when(gateway.listFiles(any(), any(), eq("."), anyInt(), anyInt())).thenReturn(
                    new WorkspaceSandboxGateway.FileList(".", List.of(new WorkspaceSandboxGateway.Entry("README.md", false, 18)), false));
            when(gateway.readFile(any(), any(), eq("README.md"), anyInt())).thenReturn(
                    new WorkspaceSandboxGateway.FileRead("README.md", "repository fixture", 18, false));
            return gateway;
        }
        @Bean @Primary InferenceExecutor inference() { return request -> {
            String context = request.messages().stream().map(message -> message.content()).reduce("", (a,b) -> a + "\n" + b);
            if (context.contains("workspaceId=")&&!request.tools().isEmpty()) assertThat(request.tools()).extracting(InferenceExecutor.InferenceToolDefinition::name)
                    .contains("file_read", "file_list").doesNotContain("document_write");
            if (context.contains("continuation-fixture")) {
                if(context.contains("Your answer stopped")&&context.contains("fail-next"))throw new InferenceProviderException(ModelCallStatus.FAILED,"FIXTURE_CONTINUATION_FAILED","fixture continuation failed",null);
                if (context.contains("Your answer stopped") && !context.contains("always-length")) {
                    assertThat(request.tools()).isEmpty();
                    return new InferenceExecutor.InferenceExecution(" Completed answer.",50,8);
                }
                return new InferenceExecutor.InferenceExecution("Partial answer.",50,8,List.of(),"length",null,Map.of());
            }
            String workspaceId = context.split("workspaceId=")[1].substring(0, 36);
            if(context.contains("code-example-fixture")) return new InferenceExecutor.InferenceExecution(
                    "An XML example:\n```xml\n<invoke name=\"file_read\"></invoke>\n```",50,8);
            if(context.contains("protocol-fixture") && !context.contains("repository fixture")) {
                boolean correction=context.contains("TOOL_PROTOCOL_CORRECTION:");
                if(correction && context.contains("correction-unknown"))
                    throw new InferenceProviderException(ModelCallStatus.UNKNOWN,"FIXTURE_UNKNOWN","fixture correction outcome unknown",null);
                if(context.contains("always-invalid") || (context.contains("README.md") && !correction))
                    return new InferenceExecutor.InferenceExecution("I will read more files\n<invoke name=\"file_read\"><parameter name=\"path\">README.md</parameter></invoke>",50,8,List.of(),"stop",null,Map.of());
                if(correction && context.contains("tool-free")) {
                    assertThat(request.tools()).isEmpty();
                    return new InferenceExecutor.InferenceExecution("Only directory evidence is available; file contents were not inspected.",50,8);
                }
            }
            if(context.contains("cancel-fixture")){
                var slow=slowCalls.get(workspaceId);slow.started.countDown();
                try{if(!slow.release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                return new InferenceExecutor.InferenceExecution("not published after cancellation",50,4,List.of(new InferenceExecutor.InferenceToolCall(
                        "cancelled-read","file_read","{\"workspaceId\":\""+workspaceId+"\",\"path\":\"README.md\"}")));
            }
            if (context.contains("repository fixture")) {
                if (context.contains("repeat-fixture") && !request.tools().isEmpty()) return new InferenceExecutor.InferenceExecution(
                        "I will read key files again",50,4,List.of(new InferenceExecutor.InferenceToolCall("repeat","file_read",
                        "{\"path\":\"README.md\",\"workspaceId\":\""+workspaceId+"\"}")));
                return new InferenceExecutor.InferenceExecution("README says repository fixture", 50, 8);
            }
            boolean listed = context.contains("README.md");
            return new InferenceExecutor.InferenceExecution("I will read key files again", 50, 4, List.of(new InferenceExecutor.InferenceToolCall(
                    listed ? "read" : "list", listed ? "file_read" : "file_list",
                    "{\"workspaceId\":\"" + workspaceId + "\",\"path\":\"" + (listed ? "README.md" : ".") + "\"}")));
        }; }
    }
}
