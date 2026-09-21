package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A read-only repository attachment, independent of writable PlanStep execution. */
@Service
public class ProjectChatWorkspaceBinding {
    static final String PHASE = "project-chat-workspace-v1";
    static final Set<String> READ_TOOLS = Set.of("file_read", "file_list");
    private final RuntimeApplicationApi runtime;
    private final ConversationApplicationApi conversations;
    private final ProjectDirectoryApplicationApi directories;
    private final WorkspaceApplicationApi workspaces;
    private final TaskApplicationApi tasks;
    private final ObjectMapper json;

    public ProjectChatWorkspaceBinding(RuntimeApplicationApi runtime,
            ConversationApplicationApi conversations, ProjectDirectoryApplicationApi directories,
            WorkspaceApplicationApi workspaces, TaskApplicationApi tasks, ObjectMapper json) {
        this.runtime = runtime; this.conversations = conversations; this.directories = directories;
        this.workspaces = workspaces; this.tasks = tasks; this.json = json;
    }

    public Binding validate(String tenant, String user, ConversationView conversation, String workspaceId) {
        if (conversation.projectId() == null || conversation.projectDirectoryId() == null
                || !tenant.equals(conversation.tenantId()) || !user.equals(conversation.userId())) throw invalid();
        var directory = directories.get(new ProjectDirectoryApplicationApi.Query(
                tenant, user, conversation.projectId(), conversation.projectDirectoryId()));
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                tenant, user, conversation.projectId(), workspaceId));
        if (directory.state() != ProjectDirectoryState.ACTIVE || directory.sourceRepositoryId() == null
                || !directory.id().equals(workspace.projectDirectoryId())
                || !directory.sourceRepositoryId().equals(workspace.sourceRepositoryId())
                || workspace.mode() != WorkspaceMode.MANAGED_GIT || workspace.state() != WorkspaceState.READY
                || !workspace.writable()) throw invalid();
        var task = tasks.getTask(new GetTaskQuery(tenant, user, conversation.projectId(), workspace.taskId()));
        if (task.state().isTerminal()) throw invalid();
        return new Binding(tenant, user, conversation.id(), conversation.projectId(), directory.id(),
                workspace.id(), task.id());
    }

    public void requireActiveDirectory(ConversationView conversation) {
        if(conversation.projectId()==null)return;
        var directory=directories.get(new ProjectDirectoryApplicationApi.Query(conversation.tenantId(),conversation.userId(),conversation.projectId(),conversation.projectDirectoryId()));
        if(directory.state()!=ProjectDirectoryState.ACTIVE)throw new BusinessException("Project root was deleted",HttpStatus.CONFLICT,"PROJECT_ROOT_ARCHIVED");
    }

    public void pin(AgentRunView run, Binding binding) {
        if (!run.tenantId().equals(binding.tenantId()) || !run.ownerId().equals(binding.userId())
                || !run.conversationId().equals(binding.conversationId()) || run.projectId() != null
                || run.chatTaskId() != null || find(run).isPresent()) throw invalid();
        try {
            runtime.createCheckpoint(new CreateCheckpointCommand(run.id(),
                    json.writeValueAsString(Map.of("phase", PHASE, "binding", binding))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw invalid();
        }
    }

    public Optional<Binding> find(AgentRunView run) {
        return runtime.findLatestCheckpointByPhase(run.id(), PHASE).map(checkpoint -> {
            try {
                Binding binding = json.treeToValue(json.readTree(checkpoint.stateSnapshot()).get("binding"), Binding.class);
                if (binding == null || !run.tenantId().equals(binding.tenantId())
                        || !run.ownerId().equals(binding.userId())
                        || !run.conversationId().equals(binding.conversationId())) throw invalid();
                return binding;
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid(); }
        });
    }

    public WorkspaceToolApplicationApi.Scope readScope(AgentRunView run, String user, String tool, String workspaceId) {
        if (!READ_TOOLS.contains(tool) || !run.ownerId().equals(user)) throw invalid();
        Binding pinned = find(run).orElseThrow(ProjectChatWorkspaceBinding::invalid);
        if (!pinned.workspaceId().equals(workspaceId)) throw invalid();
        var conversation = conversations.find(pinned.conversationId()).orElseThrow(ProjectChatWorkspaceBinding::invalid);
        Binding current = validate(run.tenantId(), user, conversation, workspaceId);
        if (!pinned.equals(current)) throw invalid();
        return new WorkspaceToolApplicationApi.Scope(run.tenantId(), user, pinned.projectId(),
                pinned.taskId(), workspaceId, run.id(), null);
    }

    private static BusinessException invalid() {
        return new BusinessException("Repository chat requires the selected directory's READY workspace and active task",
                HttpStatus.CONFLICT, "PROJECT_CHAT_WORKSPACE_INVALID");
    }

    public record Binding(String tenantId, String userId, String conversationId, String projectId,
                          String directoryId, String workspaceId, String taskId) {}
}
