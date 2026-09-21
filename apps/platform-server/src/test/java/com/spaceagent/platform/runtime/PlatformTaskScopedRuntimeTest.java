package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.application.TaskPlanApplicationService;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskPlanRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskRepository;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PlatformTaskScopedRuntimeTest {

    private static final Instant NOW = Instant.parse("2026-08-22T22:00:00Z");

    @Test
    void bindsActivePlanStepAdvancesCursorAndAppendsOrderedEvents() {
        Fixture fixture = fixture();
        var binding = fixture.firstBinding();
        RuntimeApplicationService runtime = fixture.runtime();

        var run = runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1", "version-1", "conversation-1",
                fixture.projectId(), binding.taskId(), binding.planId(), binding.stepId()));
        assertThat(run.state()).isEqualTo(AgentRunState.QUEUED);
        assertThat(run.taskPlanId()).isEqualTo(binding.planId());
        assertThat(run.planStepId()).isEqualTo(binding.stepId());

        runtime.markRunInProgress(run.id());
        assertThat(fixture.resolve(binding).planStepState()).isEqualTo(PlanStepState.IN_PROGRESS);
        assertThat(fixture.resolve(binding).taskState()).isEqualTo(TaskState.IN_PROGRESS);

        runtime.createCheckpoint(new CreateCheckpointCommand(
                run.id(), "{\"phase\":\"execute\",\"stepId\":\"compile\"}"));
        var checkpointed = runtime.findRun(run.id()).orElseThrow();
        assertThat(checkpointed.executionCursor().phase()).isEqualTo("execute");
        assertThat(checkpointed.executionCursor().stepId()).isEqualTo("compile");
        assertThat(checkpointed.executionCursor().checkpointId()).isNotBlank();

        runtime.complete(new CompleteAgentRunCommand(run.id()));
        assertThat(fixture.resolve(binding).planStepState()).isEqualTo(PlanStepState.COMPLETED);
        assertThat(fixture.resolve(binding).taskState()).isEqualTo(TaskState.COMPLETED);
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.sequence())
                .containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.type())
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.RUN_STATE_CHANGED,
                        RunEventType.CHECKPOINT_CREATED,
                        RunEventType.CURSOR_ADVANCED,
                        RunEventType.RUN_STATE_CHANGED);
    }

    @Test
    void rejectsMismatchedStepAndIncompleteDependencyWithoutMutatingRun() {
        Fixture fixture = fixture();
        var first = fixture.firstBinding();
        var second = fixture.secondBinding();
        RuntimeApplicationService runtime = fixture.runtime();

        assertThatThrownBy(() -> runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1", "version-1", "conversation-1",
                fixture.projectId(), first.taskId(), first.planId(), second.stepId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PLAN_STEP_NOT_FOUND"));

        var blockedRun = runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1", "version-1", "conversation-2",
                fixture.projectId(), second.taskId(), second.planId(), second.stepId()));
        assertThatThrownBy(() -> runtime.markRunInProgress(blockedRun.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("PLAN_STEP_DEPENDENCIES_INCOMPLETE"));
        assertThat(runtime.findRun(blockedRun.id()).orElseThrow().state())
                .isEqualTo(AgentRunState.QUEUED);
        assertThat(runtime.findEvents(blockedRun.id()))
                .extracting(event -> event.type())
                .containsExactly(RunEventType.RUN_CREATED);
    }

    private static Fixture fixture() {
        UuidGenerator ids = new UuidGenerator();
        IdentityOwnershipPort identity = (tenantId, userId) ->
                "tenant-1".equals(tenantId) && "owner-1".equals(userId);
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemoryProjectMembershipRepository memberships =
                new InMemoryProjectMembershipRepository();
        InMemoryTaskRepository tasks = new InMemoryTaskRepository();
        InMemoryTaskPlanRepository plans = new InMemoryTaskPlanRepository();
        ProjectAccessPolicy access = new ProjectAccessPolicy(projects, memberships, identity, (t, u, w) -> {});
        ProjectApplicationService projectApi = new ProjectApplicationService(
                projects, memberships, access, ids, () -> NOW);
        TaskApplicationService taskApi = new TaskApplicationService(tasks, access, ids, () -> NOW);
        TaskPlanApplicationService planApi = new TaskPlanApplicationService(
                plans, tasks, access, ids, () -> NOW, null, null);

        String projectId = projectApi.createProject(new CreateProjectCommand(
                "tenant-1", "owner-1", "Runtime Project", null)).id();
        var root = taskApi.createTask(task(projectId, null, "Root"));
        var first = taskApi.createTask(task(projectId, root.id(), "Inspect"));
        var second = taskApi.createTask(task(projectId, root.id(), "Implement"));
        var plan = planApi.createPlan(new CreateTaskPlanCommand(
                "tenant-1", "owner-1", projectId, root.id(), null, List.of(
                step("inspect", first.id(), List.of()),
                step("implement", second.id(), List.of("inspect")))));
        planApi.transition(action(projectId, root.id(), plan.id(), TaskPlanAction.PROPOSE));
        planApi.transition(action(projectId, root.id(), plan.id(), TaskPlanAction.APPROVE));
        var active = planApi.transition(action(
                projectId, root.id(), plan.id(), TaskPlanAction.ACTIVATE));

        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), new ObjectMapper(),
                ids, () -> NOW, null, null, planApi);
        return new Fixture(
                projectId, root.id(), active.id(), first.id(), active.steps().get(0).id(),
                second.id(), active.steps().get(1).id(), planApi, runtime);
    }

    private static CreateTaskCommand task(String projectId, String parentId, String title) {
        return new CreateTaskCommand(
                "tenant-1", "owner-1", projectId, parentId,
                title, "Goal " + title, null, List.of(), List.of("done"));
    }

    private static CreateTaskPlanCommand.PlanStepDraft step(
            String key, String childTaskId, List<String> dependencies) {
        return new CreateTaskPlanCommand.PlanStepDraft(
                key, childTaskId, dependencies, null, null,
                "Deliver " + key, List.of("accepted"), false);
    }

    private static TaskPlanActionCommand action(
            String projectId, String rootTaskId, String planId, TaskPlanAction action) {
        return new TaskPlanActionCommand(
                "tenant-1", "owner-1", projectId, rootTaskId, planId, action);
    }

    private record Binding(String taskId, String planId, String stepId) { }

    private record Fixture(
            String projectId,
            String rootTaskId,
            String planId,
            String firstTaskId,
            String firstStepId,
            String secondTaskId,
            String secondStepId,
            TaskPlanApplicationService plans,
            RuntimeApplicationService runtime) {

        Binding firstBinding() {
            return new Binding(firstTaskId, planId, firstStepId);
        }

        Binding secondBinding() {
            return new Binding(secondTaskId, planId, secondStepId);
        }

        com.spaceagent.platform.project.api.TaskExecutionReferenceView resolve(Binding binding) {
            return plans.resolve(new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                    "tenant-1", "owner-1", projectId, binding.taskId(),
                    binding.planId(), binding.stepId()));
        }
    }
}
