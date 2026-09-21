package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.integration.infrastructure.http.PlatformMemoryAuthorizationPolicy;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.ArchiveProjectCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.ListTasksQuery;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.TransitionTaskCommand;
import com.spaceagent.platform.project.api.UpdateTaskCommand;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectOwnershipAdapter;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformTaskApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");

    private final Set<String> tenantMemberships = new HashSet<>();
    private InMemoryProjectRepository projects;
    private InMemoryProjectMembershipRepository memberships;
    private InMemoryTaskRepository tasks;
    private IdentityOwnershipPort identityOwnership;
    private ProjectApplicationService projectService;
    private TaskApplicationService taskService;

    @BeforeEach
    void setUp() {
        projects = new InMemoryProjectRepository();
        memberships = new InMemoryProjectMembershipRepository();
        tasks = new InMemoryTaskRepository();
        identityOwnership = (tenantId, principalId) ->
                tenantMemberships.contains(tenantId + ":" + principalId);
        ProjectAccessPolicy accessPolicy = new ProjectAccessPolicy(
                projects, memberships, identityOwnership, (t, u, w) -> {});
        TimeProvider timeProvider = () -> NOW;
        UuidGenerator idGenerator = new UuidGenerator();
        projectService = new ProjectApplicationService(
                projects, memberships, accessPolicy, idGenerator, timeProvider);
        taskService = new TaskApplicationService(
                tasks, accessPolicy, idGenerator, timeProvider);
    }

    @Test
    void createPersistsDurableIntentAndSameProjectParentAncestry() {
        addTenantUser("tenant-a", "owner");
        String projectId = createProject("tenant-a", "owner", "Project A");
        TaskView parent = createTask(projectId, "owner", null, "Parent");
        TaskView child = createTask(projectId, "owner", parent.id(), "Child");

        assertEquals(projectId, child.projectId());
        assertEquals(parent.id(), child.parentTaskId());
        assertEquals("Goal Child", child.goal());
        assertEquals(List.of("constraint-Child"), child.constraints());
        assertEquals(List.of("accept-Child"), child.acceptanceCriteria());
        assertEquals(TaskState.PENDING, child.state());

        String otherProject = createProject("tenant-a", "owner", "Project B");
        BusinessException crossProjectParent = assertThrows(BusinessException.class,
                () -> createTask(otherProject, "owner", parent.id(), "Invalid Child"));
        assertEquals(HttpStatus.NOT_FOUND, crossProjectParent.getStatus());
        assertEquals("TASK_NOT_FOUND", crossProjectParent.getCode());
    }

    @Test
    void projectRolesSeparateTaskPlanningFromTaskWork() {
        addTenantUser("tenant-a", "owner");
        addTenantUser("tenant-a", "admin");
        addTenantUser("tenant-a", "member");
        addTenantUser("tenant-a", "viewer");
        String projectId = createProject("tenant-a", "owner", "Roles");
        addProjectMember(projectId, "owner", "admin", ProjectRole.ADMIN);
        addProjectMember(projectId, "owner", "member", ProjectRole.MEMBER);
        addProjectMember(projectId, "owner", "viewer", ProjectRole.VIEWER);
        TaskView task = createTask(projectId, "owner", null, "Role Task");

        assertForbidden(() -> createTask(projectId, "member", null, "Member Plan"));
        assertForbidden(() -> updateTask(projectId, task.id(), "member", "Member Edit"));
        assertForbidden(() -> transition(
                projectId, task.id(), "member", TaskTransition.MARK_READY));
        assertForbidden(() -> transition(
                projectId, task.id(), "viewer", TaskTransition.START));

        TaskView edited = updateTask(projectId, task.id(), "admin", "Admin Edit");
        assertEquals("Admin Edit", edited.title());
        assertEquals(TaskState.READY, transition(
                projectId, task.id(), "admin", TaskTransition.MARK_READY).state());
        assertEquals(TaskState.IN_PROGRESS, transition(
                projectId, task.id(), "member", TaskTransition.START).state());
        assertEquals(TaskState.BLOCKED, transition(
                projectId, task.id(), "member", TaskTransition.BLOCK).state());
        assertEquals(TaskState.READY, transition(
                projectId, task.id(), "admin", TaskTransition.MARK_READY).state());
        assertEquals(TaskState.IN_PROGRESS, transition(
                projectId, task.id(), "member", TaskTransition.START).state());
        assertEquals(TaskState.COMPLETED, transition(
                projectId, task.id(), "member", TaskTransition.COMPLETE).state());

        assertEquals(task.id(), taskService.getTask(new GetTaskQuery(
                "tenant-a", "viewer", projectId, task.id())).id());
        BusinessException terminalEdit = assertThrows(BusinessException.class,
                () -> updateTask(projectId, task.id(), "admin", "Too Late"));
        assertEquals("TASK_STATE_CONFLICT", terminalEdit.getCode());
    }

    @Test
    void invalidTransitionsAndArchivedProjectMutationsFailClosed() {
        addTenantUser("tenant-a", "owner");
        String projectId = createProject("tenant-a", "owner", "Lifecycle");
        TaskView task = createTask(projectId, "owner", null, "Lifecycle Task");

        BusinessException invalidStart = assertThrows(BusinessException.class,
                () -> transition(projectId, task.id(), "owner", TaskTransition.START));
        assertEquals(HttpStatus.CONFLICT, invalidStart.getStatus());
        assertEquals("TASK_STATE_CONFLICT", invalidStart.getCode());

        projectService.archiveProject(new ArchiveProjectCommand(
                "tenant-a", "owner", projectId));
        assertEquals(task.id(), taskService.getTask(new GetTaskQuery(
                "tenant-a", "owner", projectId, task.id())).id());
        BusinessException archivedCreate = assertThrows(BusinessException.class,
                () -> createTask(projectId, "owner", null, "Archived Task"));
        assertEquals("PROJECT_ARCHIVED", archivedCreate.getCode());
        BusinessException archivedTransition = assertThrows(BusinessException.class,
                () -> transition(projectId, task.id(), "owner", TaskTransition.MARK_READY));
        assertEquals("PROJECT_ARCHIVED", archivedTransition.getCode());
    }

    @Test
    void queryIsTenantScopedAndListsOnlyTasksFromRequestedProject() {
        addTenantUser("tenant-a", "owner-a");
        addTenantUser("tenant-b", "owner-b");
        addTenantUser("tenant-b", "owner-a");
        String projectA = createProject("tenant-a", "owner-a", "Tenant A");
        String projectB = createProject("tenant-b", "owner-b", "Tenant B");
        TaskView taskA = createTask(projectA, "owner-a", null, "A");
        taskService.createTask(new CreateTaskCommand(
                "tenant-b", "owner-b", projectB, null, "B", "Goal B", null,
                List.of(), List.of()));

        assertEquals(List.of(taskA.id()), taskService.listTasks(new ListTasksQuery(
                "tenant-a", "owner-a", projectA)).stream().map(TaskView::id).toList());
        BusinessException crossTenant = assertThrows(BusinessException.class,
                () -> taskService.getTask(new GetTaskQuery(
                        "tenant-b", "owner-a", projectA, taskA.id())));
        assertEquals("PROJECT_NOT_FOUND", crossTenant.getCode());
    }

    @Test
    void taskMemoryAuthorizationResolvesCanonicalProject() {
        addTenantUser("tenant-a", "owner");
        addTenantUser("tenant-a", "member");
        addTenantUser("tenant-a", "viewer");
        String projectId = createProject("tenant-a", "owner", "Task Memory");
        addProjectMember(projectId, "owner", "member", ProjectRole.MEMBER);
        addProjectMember(projectId, "owner", "viewer", ProjectRole.VIEWER);
        TaskView task = createTask(projectId, "owner", null, "Memory Task");
        InMemoryProjectOwnershipAdapter ownership = new InMemoryProjectOwnershipAdapter(
                projects, memberships, identityOwnership, tasks);
        PlatformMemoryAuthorizationPolicy policy = new PlatformMemoryAuthorizationPolicy(ownership);

        assertEquals(projectId, ownership.findProjectIdByTask(task.id()).orElseThrow());
        assertEquals(projectId, policy.requireTaskProjectAccess(
                task.id(), projectId, "tenant-a", "member"));
        assertFalse(ownership.isOwnerOrMember(projectId, "viewer"));
        assertThrows(BusinessException.class,
                () -> policy.requireTaskProjectAccess(task.id(), projectId, "tenant-a", "viewer"));
        assertTrue(ownership.isOwnerOrMember(projectId, "member"));
    }

    private String createProject(String tenantId, String ownerId, String name) {
        return projectService.createProject(new CreateProjectCommand(
                tenantId, ownerId, name, null)).id();
    }

    private TaskView createTask(
            String projectId,
            String userId,
            String parentTaskId,
            String title) {
        return taskService.createTask(new CreateTaskCommand(
                "tenant-a", userId, projectId, parentTaskId, title,
                "Goal " + title, "Description " + title,
                List.of("constraint-" + title), List.of("accept-" + title)));
    }

    private TaskView updateTask(
            String projectId,
            String taskId,
            String userId,
            String title) {
        return taskService.updateTask(new UpdateTaskCommand(
                "tenant-a", userId, projectId, taskId,
                title, null, null, false,
                null, false, null, false));
    }

    private TaskView transition(
            String projectId,
            String taskId,
            String userId,
            TaskTransition transition) {
        return taskService.transitionTask(new TransitionTaskCommand(
                "tenant-a", userId, projectId, taskId, transition));
    }

    private void addProjectMember(
            String projectId,
            String ownerId,
            String userId,
            ProjectRole role) {
        projectService.addMember(new AddProjectMemberCommand(
                "tenant-a", ownerId, projectId, userId, role));
    }

    private void addTenantUser(String tenantId, String userId) {
        tenantMemberships.add(tenantId + ":" + userId);
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("PROJECT_ACCESS_DENIED", exception.getCode());
    }
}
