package com.spaceagent.platform.conversation;

import com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.application.ConversationApplicationService;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryMessageRepository;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformConversationActiveTaskTest {

    @Test
    void participantCanBindSwitchAndClearOnlySameProjectTask() {
        Instant now = Instant.parse("2026-08-22T19:00:00Z");
        IdentityOwnershipPort identity = (tenantId, userId) ->
                "tenant-1".equals(tenantId) && List.of("owner", "other").contains(userId);
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryProjectMembershipRepository memberships = new InMemoryProjectMembershipRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        ProjectAccessPolicy access = new ProjectAccessPolicy(projects, memberships, identity, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        ProjectApplicationService projectApi = new ProjectApplicationService(
                projects, memberships, access, ids, () -> now);
        TaskApplicationService taskApi = new TaskApplicationService(
                taskRepository, access, ids, () -> now);
        ConversationApplicationService conversations = new ConversationApplicationService(
                new InMemoryConversationRepository(), new InMemoryMessageRepository(),
                ids, () -> now, taskApi);

        String projectId = projectApi.createProject(new CreateProjectCommand(
                "tenant-1", "owner", "Conversation Project", null)).id();
        var root = taskApi.createTask(new CreateTaskCommand(
                "tenant-1", "owner", projectId, null,
                "Root", "Root goal", null, List.of(), List.of()));
        var child = taskApi.createTask(new CreateTaskCommand(
                "tenant-1", "owner", projectId, root.id(),
                "Child", "Child goal", null, List.of(), List.of()));
        var conversation = conversations.start(new StartConversationCommand(
                projectId, null, root.id(), "tenant-1", "owner", "agent-1", "Project chat"));
        assertThat(conversation.activeTaskId()).isEqualTo(root.id());

        var switched = conversations.setActiveTask(new SetConversationActiveTaskCommand(
                "tenant-1", "owner", conversation.id(), child.id()));
        assertThat(switched.activeTaskId()).isEqualTo(child.id());
        assertThat(conversations.setActiveTask(new SetConversationActiveTaskCommand(
                "tenant-1", "owner", conversation.id(), null)).activeTaskId()).isNull();

        String otherProject = projectApi.createProject(new CreateProjectCommand(
                "tenant-1", "owner", "Other", null)).id();
        var foreignTask = taskApi.createTask(new CreateTaskCommand(
                "tenant-1", "owner", otherProject, null,
                "Foreign", "Foreign goal", null, List.of(), List.of()));
        assertThatThrownBy(() -> conversations.setActiveTask(
                new SetConversationActiveTaskCommand(
                        "tenant-1", "owner", conversation.id(), foreignTask.id())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_NOT_FOUND"));
        assertThatThrownBy(() -> conversations.setActiveTask(
                new SetConversationActiveTaskCommand(
                        "tenant-1", "other", conversation.id(), root.id())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> conversations.start(new StartConversationCommand(
                null, null, root.id(), "tenant-1", "owner", "agent-1", "Invalid")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("CONVERSATION_PROJECT_REQUIRED"));
    }
}
