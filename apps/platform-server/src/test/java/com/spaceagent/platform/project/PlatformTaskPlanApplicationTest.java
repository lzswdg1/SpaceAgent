package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.ChatTaskPlanActionCommand;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.ListChatTaskPlansQuery;
import com.spaceagent.platform.project.api.ListChatTasksQuery;
import com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand;
import com.spaceagent.platform.project.api.TransitionPlanStepExecutionCommand;
import com.spaceagent.platform.project.api.PlanStepExecutionAction;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.ListTaskPlansQuery;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.application.TaskPlanApplicationService;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskPlanRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryTaskRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformTaskPlanApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T18:00:00Z");

    private final Set<String> tenantMemberships = new HashSet<>();
    private ProjectApplicationService projects;
    private TaskApplicationService tasks;
    private TaskPlanApplicationService plans;
    private String projectId;
    private TaskView root;
    private TaskView firstChild;
    private TaskView secondChild;

    @BeforeEach
    void setUp() {
        tenantMemberships.addAll(List.of(
                "tenant-1:owner", "tenant-1:admin",
                "tenant-1:member", "tenant-1:viewer"));
        IdentityOwnershipPort identity = (tenantId, userId) ->
                tenantMemberships.contains(tenantId + ":" + userId);
        InMemoryProjectRepository projectRepository = new InMemoryProjectRepository();
        InMemoryProjectMembershipRepository memberships =
                new InMemoryProjectMembershipRepository();
        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        ProjectAccessPolicy access = new ProjectAccessPolicy(
                projectRepository, memberships, identity, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        projects = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW);
        tasks = new TaskApplicationService(taskRepository, access, ids, () -> NOW);
        plans = new TaskPlanApplicationService(
                new InMemoryTaskPlanRepository(), taskRepository, access, ids, () -> NOW,
                null, null);

        projectId = projects.createProject(new CreateProjectCommand(
                "tenant-1", "owner", "TaskPlan Project", null)).id();
        projects.addMember(new AddProjectMemberCommand(
                "tenant-1", "owner", projectId, "admin", ProjectRole.ADMIN));
        projects.addMember(new AddProjectMemberCommand(
                "tenant-1", "owner", projectId, "member", ProjectRole.MEMBER));
        projects.addMember(new AddProjectMemberCommand(
                "tenant-1", "owner", projectId, "viewer", ProjectRole.VIEWER));
        root = createTask(projectId, null, "Root");
        firstChild = createTask(projectId, root.id(), "Inspect");
        secondChild = createTask(projectId, root.id(), "Implement");
    }

    @Test
    void planLifecyclePersistsDagApprovalVersionsAndCurrentPointer() {
        var firstPlan = plans.createPlan(planCommand("owner", List.of(
                step("inspect", firstChild.id(), List.of()),
                step("implement", secondChild.id(), List.of("inspect")))));
        assertThat(firstPlan.versionNumber()).isEqualTo(1);
        assertThat(firstPlan.status()).isEqualTo(TaskPlanStatus.DRAFT);
        assertThat(firstPlan.steps()).hasSize(2);
        assertThat(firstPlan.steps().get(1).dependencyStepIds())
                .containsExactly(firstPlan.steps().get(0).id());

        assertThat(transition("owner", firstPlan.id(), TaskPlanAction.PROPOSE).status())
                .isEqualTo(TaskPlanStatus.PROPOSED);
        var approved = transition("admin", firstPlan.id(), TaskPlanAction.APPROVE);
        assertThat(approved.status()).isEqualTo(TaskPlanStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("admin");
        assertThat(transition("admin", firstPlan.id(), TaskPlanAction.ACTIVATE).status())
                .isEqualTo(TaskPlanStatus.ACTIVE);
        assertThat(transition("admin", firstPlan.id(), TaskPlanAction.ACTIVATE).status())
                .isEqualTo(TaskPlanStatus.ACTIVE);
        assertThat(task(root.id()).currentTaskPlanId()).isEqualTo(firstPlan.id());

        var secondPlan = plans.createPlan(planCommand("owner", List.of(
                step("inspect-v2", firstChild.id(), List.of()),
                step("implement-v2", secondChild.id(), List.of("inspect-v2")))));
        assertThat(secondPlan.versionNumber()).isEqualTo(2);
        transition("owner", secondPlan.id(), TaskPlanAction.PROPOSE);
        transition("owner", secondPlan.id(), TaskPlanAction.APPROVE);
        assertThatThrownBy(() -> transition("owner", secondPlan.id(), TaskPlanAction.ACTIVATE))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_PLAN_ACTIVE_CONFLICT"));

        transition("owner", firstPlan.id(), TaskPlanAction.COMPLETE);
        transition("owner", secondPlan.id(), TaskPlanAction.ACTIVATE);
        assertThat(task(root.id()).currentTaskPlanId()).isEqualTo(secondPlan.id());
        transition("owner", secondPlan.id(), TaskPlanAction.CANCEL);
        assertThat(task(root.id()).currentTaskPlanId()).isNull();
        assertThat(plans.listPlans(new ListTaskPlansQuery(
                "tenant-1", "viewer", projectId, root.id()))).hasSize(2);
    }

    @Test
    void rolesMalformedGraphsAndCrossProjectChildrenFailClosed() {
        assertThatThrownBy(() -> plans.createPlan(planCommand("member", List.of(
                step("member", firstChild.id(), List.of())))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> plans.createPlan(planCommand("owner", List.of(
                step("a", firstChild.id(), List.of("b")),
                step("b", secondChild.id(), List.of("a"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_PLAN_INPUT_INVALID"));
        assertThatThrownBy(() -> plans.createPlan(planCommand("owner", List.of(
                step("a", firstChild.id(), List.of("missing"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_PLAN_INPUT_INVALID"));

        String otherProject = projects.createProject(new CreateProjectCommand(
                "tenant-1", "owner", "Other Project", null)).id();
        TaskView otherRoot = createTask(otherProject, null, "Other Root");
        TaskView otherChild = createTask(otherProject, otherRoot.id(), "Other Child");
        assertThatThrownBy(() -> plans.createPlan(planCommand("owner", List.of(
                step("foreign", otherChild.id(), List.of())))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_NOT_FOUND"));
    }

    @Test
    void projectStepLifecycleStartsRootAndCompletesPlanAndRootAfterFinalStep() {
        var plan = plans.createPlan(planCommand("owner", List.of(
                step("inspect", firstChild.id(), List.of()),
                step("implement", secondChild.id(), List.of("inspect")))));
        transition("owner", plan.id(), TaskPlanAction.PROPOSE);
        transition("owner", plan.id(), TaskPlanAction.APPROVE);
        transition("owner", plan.id(), TaskPlanAction.ACTIVATE);

        var first = plan.steps().get(0);
        var second = plan.steps().get(1);
        plans.transition(projectStep(plan.id(), first, PlanStepExecutionAction.START));
        assertThat(task(root.id()).state()).isEqualTo(TaskState.IN_PROGRESS);
        assertThat(task(firstChild.id()).state()).isEqualTo(TaskState.IN_PROGRESS);

        plans.transition(projectStep(plan.id(), first, PlanStepExecutionAction.COMPLETE));
        assertThat(plans.getPlan(new com.spaceagent.platform.project.api.GetTaskPlanQuery(
                "tenant-1", "owner", projectId, root.id(), plan.id())).status())
                .isEqualTo(TaskPlanStatus.ACTIVE);
        assertThat(task(root.id()).state()).isEqualTo(TaskState.IN_PROGRESS);

        plans.transition(projectStep(plan.id(), second, PlanStepExecutionAction.START));
        plans.transition(projectStep(plan.id(), second, PlanStepExecutionAction.COMPLETE));

        assertThat(plans.getPlan(new com.spaceagent.platform.project.api.GetTaskPlanQuery(
                "tenant-1", "owner", projectId, root.id(), plan.id())).status())
                .isEqualTo(TaskPlanStatus.COMPLETED);
        assertThat(task(root.id()).state()).isEqualTo(TaskState.COMPLETED);
        assertThat(task(firstChild.id()).state()).isEqualTo(TaskState.COMPLETED);
        assertThat(task(secondChild.id()).state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void projectStepFailureCancelsPlanAndFailsRoot() {
        var plan = plans.createPlan(planCommand("owner", List.of(
                step("inspect", firstChild.id(), List.of()))));
        transition("owner", plan.id(), TaskPlanAction.PROPOSE);
        transition("owner", plan.id(), TaskPlanAction.APPROVE);
        transition("owner", plan.id(), TaskPlanAction.ACTIVATE);
        var first = plan.steps().getFirst();

        plans.transition(projectStep(plan.id(), first, PlanStepExecutionAction.START));
        plans.transition(projectStep(plan.id(), first, PlanStepExecutionAction.FAIL));

        assertThat(plans.getPlan(new com.spaceagent.platform.project.api.GetTaskPlanQuery(
                "tenant-1", "owner", projectId, root.id(), plan.id())).status())
                .isEqualTo(TaskPlanStatus.CANCELLED);
        assertThat(task(root.id()).state()).isEqualTo(TaskState.FAILED);
        assertThat(task(firstChild.id()).state()).isEqualTo(TaskState.FAILED);
    }

    @Test
    void chatProposalCreatesChildDagAndSupportsOwnerReviewLifecycle() {
        String conversationId = "conversation-chat-1";
        var chatRoot = tasks.createOrGetChatRootTask(new CreateChatRootTaskCommand(
                "tenant-1", "owner", conversationId, "message-chat-1",
                "Research", "Research durable planning"));
        String runId = UUID.randomUUID().toString();
        var command = new CreateChatTaskPlanProposalCommand(
                "tenant-1", "owner", conversationId, chatRoot.id(), runId, null,
                "Collect evidence, then synthesize", List.of(
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "collect", "Collect trustworthy evidence", List.of()),
                new CreateChatTaskPlanProposalCommand.StepProposal(
                        "synthesize", "Synthesize the answer", List.of("collect"))));

        var proposed = plans.createChatProposal(command);
        assertThat(proposed.scope()).isEqualTo("CHAT");
        assertThat(proposed.status()).isEqualTo(TaskPlanStatus.PROPOSED);
        assertThat(proposed.conversationId()).isEqualTo(conversationId);
        assertThat(proposed.sourceAgentRunId()).isEqualTo(runId);
        assertThat(proposed.proposalHash()).startsWith("sha256:");
        assertThat(proposed.steps()).hasSize(2);
        assertThat(proposed.steps().get(1).dependencyStepIds())
                .containsExactly(proposed.steps().get(0).id());
        assertThat(tasks.getChatTask(new GetChatTaskQuery(
                "tenant-1", "owner", conversationId,
                proposed.steps().get(0).childTaskId())).parentTaskId())
                .isEqualTo(chatRoot.id());
        assertThat(tasks.listChatTasks(new ListChatTasksQuery(
                "tenant-1", "owner", conversationId, 100)))
                .extracting(TaskView::id).containsExactly(chatRoot.id());
        assertThat(plans.createChatProposal(command).id()).isEqualTo(proposed.id());

        var approved = plans.transitionChatPlan(new ChatTaskPlanActionCommand(
                "tenant-1", "owner", conversationId, chatRoot.id(), proposed.id(),
                TaskPlanAction.APPROVE));
        assertThat(approved.approvedBy()).isEqualTo("owner");
        assertThat(plans.transitionChatPlan(new ChatTaskPlanActionCommand(
                "tenant-1", "owner", conversationId, chatRoot.id(), proposed.id(),
                TaskPlanAction.ACTIVATE)).status()).isEqualTo(TaskPlanStatus.ACTIVE);
        assertThat(tasks.getChatTask(new GetChatTaskQuery(
                "tenant-1", "owner", conversationId, chatRoot.id())).currentTaskPlanId())
                .isEqualTo(proposed.id());
        assertThat(plans.listChatPlans(new ListChatTaskPlansQuery(
                "tenant-1", "owner", conversationId, chatRoot.id())))
                .extracting(value -> value.id()).containsExactly(proposed.id());

        var firstStep = proposed.steps().get(0);
        var secondStep = proposed.steps().get(1);
        assertThatThrownBy(() -> plans.transitionChatPlanStep(
                chatStep(conversationId, chatRoot.id(), proposed.id(), secondStep,
                        PlanStepExecutionAction.START)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("PLAN_STEP_DEPENDENCIES_INCOMPLETE"));
        plans.transitionChatPlanStep(chatStep(
                conversationId, chatRoot.id(), proposed.id(), firstStep,
                PlanStepExecutionAction.START));
        plans.transitionChatPlanStep(chatStep(
                conversationId, chatRoot.id(), proposed.id(), firstStep,
                PlanStepExecutionAction.COMPLETE));
        plans.transitionChatPlanStep(chatStep(
                conversationId, chatRoot.id(), proposed.id(), secondStep,
                PlanStepExecutionAction.START));
        plans.transitionChatPlanStep(chatStep(
                conversationId, chatRoot.id(), proposed.id(), secondStep,
                PlanStepExecutionAction.COMPLETE));

        assertThatThrownBy(() -> plans.createChatProposal(
                new CreateChatTaskPlanProposalCommand(
                        "tenant-1", "owner", conversationId, chatRoot.id(), runId, null,
                        "Changed strategy", command.steps())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("CHAT_TASK_PLAN_IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> plans.getChatPlan(
                new com.spaceagent.platform.project.api.GetChatTaskPlanQuery(
                        "tenant-1", "admin", conversationId, chatRoot.id(), proposed.id())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void chatProposalRejectsCyclesAndNonRootTasks() {
        String conversationId = "conversation-chat-2";
        var root = tasks.createOrGetChatRootTask(new CreateChatRootTaskCommand(
                "tenant-1", "owner", conversationId, "message-chat-2", "Plan", "Plan goal"));
        var invalid = new CreateChatTaskPlanProposalCommand(
                "tenant-1", "owner", conversationId, root.id(), UUID.randomUUID().toString(), null,
                "Invalid", List.of(
                new CreateChatTaskPlanProposalCommand.StepProposal("a", "A", List.of("b")),
                new CreateChatTaskPlanProposalCommand.StepProposal("b", "B", List.of("a"))));
        assertThatThrownBy(() -> plans.createChatProposal(invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TASK_PLAN_INPUT_INVALID"));
    }

    private CreateTaskPlanCommand planCommand(
            String userId,
            List<CreateTaskPlanCommand.PlanStepDraft> steps) {
        return new CreateTaskPlanCommand(
                "tenant-1", userId, projectId, root.id(), null, steps);
    }

    private TransitionChatPlanStepExecutionCommand chatStep(
            String conversationId, String rootTaskId, String planId,
            com.spaceagent.platform.project.api.PlanStepView step,
            PlanStepExecutionAction action) {
        return new TransitionChatPlanStepExecutionCommand(
                "tenant-1", "owner", conversationId, rootTaskId,
                step.childTaskId(), planId, step.id(), action);
    }

    private TransitionPlanStepExecutionCommand projectStep(
            String planId,
            com.spaceagent.platform.project.api.PlanStepView step,
            PlanStepExecutionAction action) {
        return new TransitionPlanStepExecutionCommand(
                "tenant-1", "owner", projectId, step.childTaskId(),
                planId, step.id(), action);
    }

    private static CreateTaskPlanCommand.PlanStepDraft step(
            String key,
            String childTaskId,
            List<String> dependencies) {
        return new CreateTaskPlanCommand.PlanStepDraft(
                key, childTaskId, dependencies, "coding", null,
                "Deliver " + key, List.of("accepted " + key), false);
    }

    private com.spaceagent.platform.project.api.TaskPlanView transition(
            String userId,
            String planId,
            TaskPlanAction action) {
        return plans.transition(new TaskPlanActionCommand(
                "tenant-1", userId, projectId, root.id(), planId, action));
    }

    private TaskView createTask(String targetProjectId, String parentId, String title) {
        return tasks.createTask(new CreateTaskCommand(
                "tenant-1", "owner", targetProjectId, parentId,
                title, "Goal " + title, null, List.of(), List.of("done")));
    }

    private TaskView task(String taskId) {
        return tasks.getTask(new GetTaskQuery(
                "tenant-1", "owner", projectId, taskId));
    }
}
