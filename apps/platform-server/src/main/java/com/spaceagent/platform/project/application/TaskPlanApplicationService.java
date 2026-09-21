package com.spaceagent.platform.project.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.ChatTaskPlanActionCommand;
import com.spaceagent.platform.project.api.GetChatTaskPlanQuery;
import com.spaceagent.platform.project.api.GetChatTaskPlanBySourceRunQuery;
import com.spaceagent.platform.project.api.GetTaskPlanQuery;
import com.spaceagent.platform.project.api.ListChatTaskPlansQuery;
import com.spaceagent.platform.project.api.ListTaskPlansQuery;
import com.spaceagent.platform.project.api.PlanStepView;
import com.spaceagent.platform.project.api.PlanStepExecutionAction;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.ChatTaskExecutionReferenceView;
import com.spaceagent.platform.project.api.ChatPlanStepExecutionReferenceView;
import com.spaceagent.platform.project.api.ResolveChatPlanStepExecutionQuery;
import com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand;
import com.spaceagent.platform.project.api.ResolveChatTaskExecutionQuery;
import com.spaceagent.platform.project.api.TaskExecutionReferenceView;
import com.spaceagent.platform.project.api.TransitionPlanStepExecutionCommand;
import com.spaceagent.platform.project.domain.PlanStep;
import com.spaceagent.platform.project.domain.PlanStepDependency;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.Task;
import com.spaceagent.platform.project.domain.TaskPlan;
import com.spaceagent.platform.project.domain.TaskPlanRepository;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskRepository;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

/** Project-owned PROJECT/CHAT TaskPlan structure and approval lifecycle. */
@Service
public class TaskPlanApplicationService implements TaskPlanApplicationApi, TaskExecutionApplicationApi {

    private final TaskPlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final AgentApplicationApi agentApi;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskPlanApplicationService(
            TaskPlanRepository planRepository,
            TaskRepository taskRepository,
            ProjectAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            AgentApplicationApi agentApi) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.accessPolicy = accessPolicy;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.agentApi = agentApi;
    }

    public TaskPlanApplicationService(
            TaskPlanRepository planRepository, TaskRepository taskRepository,
            ProjectAccessPolicy accessPolicy, IdGenerator idGenerator, TimeProvider timeProvider,
            AgentApplicationApi agentApi, Object ignoredReleaseDependency) {
        this(planRepository, taskRepository, accessPolicy, idGenerator, timeProvider, agentApi);
    }

    @Override
    @Transactional
    public TaskPlanView createPlan(CreateTaskPlanCommand command) {
        Project project = requireManagedActiveProject(
                command.tenantId(), command.userId(), command.projectId());
        Task root = requireTask(project.id(), command.rootTaskId(), true);
        if (root.parentTaskId() != null) {
            throw invalidInput("TaskPlan rootTaskId must reference a root Task");
        }
        List<CreateTaskPlanCommand.PlanStepDraft> drafts = command.steps();
        if (drafts.isEmpty() || drafts.size() > 100) {
            throw invalidInput("TaskPlan must contain between 1 and 100 steps");
        }
        String generatedByAgentId = resolveGeneratedByAgent(
                command.tenantId(), command.userId(), command.generatedByAgentId(),
                command.generatedByConfigurationHash());
        validateDraftGraph(drafts);

        Instant now = timeProvider.now();
        String planId = idGenerator.nextId();
        Map<String, String> stepIds = new LinkedHashMap<>();
        List<PlanStep> steps = new ArrayList<>();
        Set<String> childTaskIds = new HashSet<>();
        for (int index = 0; index < drafts.size(); index++) {
            CreateTaskPlanCommand.PlanStepDraft draft = drafts.get(index);
            Task child = requireTask(project.id(), draft.childTaskId(), false);
            if (!root.id().equals(child.parentTaskId())) {
                throw invalidInput("Every PlanStep must reference a direct child of the root Task");
            }
            if (!childTaskIds.add(child.id())) {
                throw invalidInput("A child Task cannot appear in multiple PlanSteps");
            }
            validatePreferredAgent(command.tenantId(), command.userId(), draft.preferredAgentId());
            String stepId = idGenerator.nextId();
            stepIds.put(draft.stepKey().trim(), stepId);
            try {
                steps.add(new PlanStep(
                        stepId, planId, project.id(), draft.stepKey(), index,
                        child.id(), normalizeOptional(draft.requiredCapability()),
                        normalizeOptional(draft.preferredAgentId()), draft.expectedOutput(),
                        draft.acceptanceCriteria(), draft.approvalRequired(),
                        PlanStepState.PENDING, now, now));
            } catch (IllegalArgumentException exception) {
                throw invalidInput(exception.getMessage());
            }
        }
        List<PlanStepDependency> dependencies = new ArrayList<>();
        for (CreateTaskPlanCommand.PlanStepDraft draft : drafts) {
            String stepId = stepIds.get(draft.stepKey().trim());
            draft.dependsOnStepKeys().stream().map(String::trim).distinct().forEach(key ->
                    dependencies.add(new PlanStepDependency(planId, stepId, stepIds.get(key))));
        }
        TaskPlan plan = TaskPlan.createProject(
                planId, project.id(), command.tenantId(), command.userId(), root.id(),
                planRepository.nextVersionNumber(root.id()), TaskPlanStatus.DRAFT,
                normalizeOptional(command.generatedByConfigurationHash()), generatedByAgentId,
                normalizeOptional(command.generatedByRunConfigurationSnapshotId()), command.userId(), now);
        try {
            planRepository.insert(plan, steps, dependencies);
        } catch (DataIntegrityViolationException | IllegalStateException exception) {
            throw new BusinessException(
                    "TaskPlan persistence constraint violated",
                    HttpStatus.CONFLICT,
                    "TASK_PLAN_PERSISTENCE_CONFLICT");
        }
        return toView(plan);
    }

    @Override
    @Transactional
    public TaskPlanView createChatProposal(CreateChatTaskPlanProposalCommand command) {
        requireChatScope(command.tenantId(), command.userId(), command.conversationId());
        requireUuid(command.sourceAgentRunId(), "CHAT_TASK_PLAN_SOURCE_RUN_INVALID");
        Task root = requireChatRoot(
                command.tenantId(), command.userId(), command.conversationId(),
                command.rootTaskId(), true);
        if (root.state() != TaskState.IN_PROGRESS) {
            throw new BusinessException(
                    "Chat Root Task is not accepting a plan proposal",
                    HttpStatus.CONFLICT, "CHAT_TASK_PLAN_ROOT_NOT_ACTIVE");
        }
        String generatedByAgentId = resolveGeneratedByAgent(
                command.tenantId(), command.userId(), command.generatedByAgentId(),
                command.generatedByConfigurationHash());
        String strategy = requireBoundedText(
                command.strategySummary(), "strategySummary", 4_000);
        List<CreateChatTaskPlanProposalCommand.StepProposal> proposals = command.steps();
        if (proposals.isEmpty() || proposals.size() > 32) {
            throw invalidInput("Chat TaskPlan must contain between 1 and 32 steps");
        }
        validateChatProposalGraph(proposals);
        String proposalHash = proposalHash(strategy, proposals);

        planRepository.lockChatProposalSource(command.sourceAgentRunId());
        TaskPlan existing = planRepository.findBySourceAgentRunId(command.sourceAgentRunId())
                .orElse(null);
        if (existing != null) {
            if (!root.id().equals(existing.rootTaskId())
                    || !command.conversationId().equals(existing.conversationId())
                    || !command.tenantId().equals(existing.tenantId())
                    || !command.userId().equals(existing.ownerUserId())
                    || !java.util.Objects.equals(
                            normalizeOptional(command.generatedByConfigurationHash()),
                            existing.generatedByConfigurationHash())
                    || !java.util.Objects.equals(generatedByAgentId, existing.generatedByAgentId())
                    || !proposalHash.equals(existing.proposalHash())) {
                throw new BusinessException(
                        "AgentRun is already bound to another Chat TaskPlan proposal",
                        HttpStatus.CONFLICT, "CHAT_TASK_PLAN_IDEMPOTENCY_CONFLICT");
            }
            return toView(existing);
        }

        Instant now = timeProvider.now();
        String planId = idGenerator.nextId();
        Map<String, String> stepIds = new LinkedHashMap<>();
        List<PlanStep> steps = new ArrayList<>();
        for (int index = 0; index < proposals.size(); index++) {
            CreateChatTaskPlanProposalCommand.StepProposal proposal = proposals.get(index);
            String stepKey = proposal.stepKey().trim();
            String goal = requireBoundedText(proposal.goal(), "goal", 8_000);
            String childId = idGenerator.nextId();
            Task child = Task.createChatChild(
                    childId, command.tenantId(), command.userId(), command.conversationId(),
                    root.id(), stepKey, goal, List.of("Complete planned step: " + goal), now);
            taskRepository.save(child);
            String stepId = idGenerator.nextId();
            stepIds.put(stepKey, stepId);
            steps.add(new PlanStep(
                    stepId, planId, null, stepKey, index, childId,
                    null, null, goal, child.acceptanceCriteria(), false,
                    PlanStepState.PENDING, now, now, command.conversationId()));
        }
        List<PlanStepDependency> dependencies = new ArrayList<>();
        for (CreateChatTaskPlanProposalCommand.StepProposal proposal : proposals) {
            String stepId = stepIds.get(proposal.stepKey().trim());
            proposal.dependsOnStepKeys().stream().map(String::trim).distinct().forEach(key ->
                    dependencies.add(new PlanStepDependency(planId, stepId, stepIds.get(key))));
        }
        TaskPlan plan = TaskPlan.createChatProposal(
                planId, command.tenantId(), command.userId(), command.conversationId(), root.id(),
                planRepository.nextVersionNumber(root.id()),
                normalizeOptional(command.generatedByConfigurationHash()),
                generatedByAgentId, command.sourceAgentRunId(), proposalHash, strategy, now);
        try {
            planRepository.insert(plan, steps, dependencies);
        } catch (DataIntegrityViolationException | IllegalStateException exception) {
            throw new BusinessException(
                    "Chat TaskPlan persistence constraint violated",
                    HttpStatus.CONFLICT, "CHAT_TASK_PLAN_PERSISTENCE_CONFLICT");
        }
        return toView(plan);
    }

    @Override
    public TaskPlanView getChatPlan(GetChatTaskPlanQuery query) {
        Task root = requireChatRoot(
                query.tenantId(), query.userId(), query.conversationId(),
                query.rootTaskId(), false);
        return toView(requireChatPlan(root, query.taskPlanId(), false));
    }

    @Override
    public TaskPlanView getChatPlanBySourceRun(GetChatTaskPlanBySourceRunQuery query) {
        requireChatScope(query.tenantId(), query.userId(), query.conversationId());
        requireUuid(query.sourceAgentRunId(), "CHAT_TASK_PLAN_SOURCE_RUN_INVALID");
        TaskPlan plan = planRepository.findBySourceAgentRunId(query.sourceAgentRunId())
                .filter(value -> value.projectId() == null)
                .filter(value -> query.tenantId().equals(value.tenantId()))
                .filter(value -> query.userId().equals(value.ownerUserId()))
                .filter(value -> query.conversationId().equals(value.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Chat TaskPlan not found", HttpStatus.NOT_FOUND,
                        "TASK_PLAN_NOT_FOUND"));
        requireChatRoot(
                query.tenantId(), query.userId(), query.conversationId(),
                plan.rootTaskId(), false);
        return toView(plan);
    }

    @Override
    public List<TaskPlanView> listChatPlans(ListChatTaskPlansQuery query) {
        Task root = requireChatRoot(
                query.tenantId(), query.userId(), query.conversationId(),
                query.rootTaskId(), false);
        return planRepository.findByRootTaskId(root.id()).stream()
                .filter(plan -> query.conversationId().equals(plan.conversationId()))
                .filter(plan -> query.tenantId().equals(plan.tenantId()))
                .filter(plan -> query.userId().equals(plan.ownerUserId()))
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public TaskPlanView transitionChatPlan(ChatTaskPlanActionCommand command) {
        requireChatScope(command.tenantId(), command.userId(), command.conversationId());
        Task root = requireChatRoot(
                command.tenantId(), command.userId(), command.conversationId(),
                command.rootTaskId(), true);
        TaskPlan plan = requireChatPlan(root, command.taskPlanId(), true);
        if (command.action() != TaskPlanAction.APPROVE
                && command.action() != TaskPlanAction.ACTIVATE
                && command.action() != TaskPlanAction.COMPLETE
                && command.action() != TaskPlanAction.CANCEL) {
            throw invalidInput(
                    "Chat TaskPlan action must be APPROVE, ACTIVATE, COMPLETE or CANCEL");
        }
        Instant now = timeProvider.now();
        try {
            TaskPlan updated = switch (command.action()) {
                case APPROVE -> plan.approve(command.userId(), now);
                case ACTIVATE -> activate(plan, root, now);
                case COMPLETE -> plan.complete(now);
                case CANCEL -> cancel(plan, root, now);
                default -> throw invalidInput("Unsupported Chat TaskPlan action");
            };
            planRepository.updateLifecycle(updated);
            return toView(updated);
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    exception.getMessage(), HttpStatus.CONFLICT, "TASK_PLAN_STATE_CONFLICT");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "Another TaskPlan is already active for the Chat Root Task",
                    HttpStatus.CONFLICT, "TASK_PLAN_ACTIVE_CONFLICT");
        }
    }

    @Override
    public TaskPlanView getPlan(GetTaskPlanQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        Task root = requireTask(project.id(), query.rootTaskId(), false);
        return toView(requirePlan(project.id(), root.id(), query.taskPlanId(), false));
    }

    @Override
    public List<TaskPlanView> listPlans(ListTaskPlansQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        Task root = requireTask(project.id(), query.rootTaskId(), false);
        return planRepository.findByRootTaskId(root.id()).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public TaskPlanView transition(TaskPlanActionCommand command) {
        Project project = requireManagedActiveProject(
                command.tenantId(), command.userId(), command.projectId());
        Task root = requireTask(project.id(), command.rootTaskId(), true);
        TaskPlan plan = requirePlan(project.id(), root.id(), command.taskPlanId(), true);
        TaskPlanAction action = command.action();
        if (action == null) {
            throw invalidInput("TaskPlan action is required");
        }
        Instant now = timeProvider.now();
        try {
            TaskPlan updated = switch (action) {
                case PROPOSE -> plan.propose(now);
                case APPROVE -> plan.approve(command.userId(), now);
                case ACTIVATE -> activate(plan, root, now);
                case COMPLETE -> plan.complete(now);
                case CANCEL -> cancel(plan, root, now);
            };
            planRepository.updateLifecycle(updated);
            return toView(updated);
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    exception.getMessage(), HttpStatus.CONFLICT, "TASK_PLAN_STATE_CONFLICT");
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "Another TaskPlan is already active for the root Task",
                    HttpStatus.CONFLICT,
                    "TASK_PLAN_ACTIVE_CONFLICT");
        }
    }

    @Override
    public TaskExecutionReferenceView resolve(ResolveTaskExecutionReferenceQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        accessPolicy.requireActive(project);
        Task task = requireTask(project.id(), query.taskId(), false);
        TaskPlan plan = requirePlanById(project.id(), query.taskPlanId(), false);
        PlanStep step = requirePlanStep(plan, task.id(), query.planStepId());
        return toExecutionView(plan, step, task);
    }

    @Override
    public ChatTaskExecutionReferenceView resolveChatTask(ResolveChatTaskExecutionQuery query) {
        Task task = taskRepository.findById(query.taskId())
                .filter(value -> value.projectId() == null)
                .filter(value -> query.tenantId().equals(value.tenantId()))
                .filter(value -> query.userId().equals(value.ownerUserId()))
                .filter(value -> query.conversationId().equals(value.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Chat Task execution reference not found",
                        HttpStatus.NOT_FOUND, "CHAT_TASK_EXECUTION_NOT_FOUND"));
        if (task.state() != TaskState.IN_PROGRESS) {
            throw new BusinessException(
                    "Chat Task is not executable: " + task.state(),
                    HttpStatus.CONFLICT, "CHAT_TASK_NOT_EXECUTABLE");
        }
        return new ChatTaskExecutionReferenceView(
                task.id(), task.conversationId(), task.sourceMessageId(), task.state());
    }

    @Override
    public ChatPlanStepExecutionReferenceView resolveChatPlanStep(
            ResolveChatPlanStepExecutionQuery query) {
        Task root = requireChatRoot(
                query.tenantId(), query.userId(), query.conversationId(),
                query.rootTaskId(), false);
        TaskPlan plan = requireChatPlan(root, query.taskPlanId(), false);
        Task child = requireChatChild(root, query.taskId(), false);
        PlanStep step = requirePlanStep(plan, child.id(), query.planStepId());
        return toChatExecutionView(plan, step, child);
    }

    @Override
    @Transactional
    public TaskExecutionReferenceView transition(TransitionPlanStepExecutionCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        Task task = requireTask(project.id(), command.taskId(), true);
        TaskPlan plan = requirePlanById(project.id(), command.taskPlanId(), true);
        Task root = requireTask(project.id(), plan.rootTaskId(), true);
        if (plan.status() != TaskPlanStatus.ACTIVE) {
            throw new BusinessException(
                    "TaskPlan must be ACTIVE for execution",
                    HttpStatus.CONFLICT,
                    "TASK_PLAN_NOT_ACTIVE");
        }
        PlanStep step = requirePlanStep(plan, task.id(), command.planStepId());
        try {
            PlanStep updated = switch (command.action()) {
                case START -> startStepAfterDependencies(plan, step);
                case COMPLETE -> step.complete(timeProvider.now());
                case FAIL -> step.fail(timeProvider.now());
                case CANCEL -> step.cancel(timeProvider.now());
            };
            Task updatedTask = transitionTask(task, command.action());
            planRepository.updateStep(updated);
            taskRepository.save(updatedTask);
            TaskPlan effectivePlan = plan;
            Task effectiveRoot = root;
            if (command.action() == PlanStepExecutionAction.START) {
                effectiveRoot = startRootTask(root);
            } else if (command.action() == PlanStepExecutionAction.COMPLETE
                    && planRepository.findSteps(plan.id()).stream()
                            .allMatch(value -> value.state() == PlanStepState.COMPLETED)) {
                effectivePlan = plan.complete(timeProvider.now());
                planRepository.updateLifecycle(effectivePlan);
                effectiveRoot = completeRootTask(root);
            } else if (command.action() == PlanStepExecutionAction.FAIL) {
                effectivePlan = plan.cancel(timeProvider.now());
                planRepository.updateLifecycle(effectivePlan);
                effectiveRoot = failRootTask(root);
            }
            if (effectiveRoot != root) taskRepository.save(effectiveRoot);
            return toExecutionView(effectivePlan, updated, updatedTask);
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    exception.getMessage(), HttpStatus.CONFLICT, "PLAN_STEP_STATE_CONFLICT");
        }
    }

    @Override
    @Transactional
    public ChatPlanStepExecutionReferenceView transitionChatPlanStep(
            TransitionChatPlanStepExecutionCommand command) {
        Task root = requireChatRoot(
                command.tenantId(), command.userId(), command.conversationId(),
                command.rootTaskId(), true);
        TaskPlan plan = requireChatPlan(root, command.taskPlanId(), true);
        if (plan.status() != TaskPlanStatus.ACTIVE) {
            throw new BusinessException(
                    "Chat TaskPlan must be ACTIVE for execution",
                    HttpStatus.CONFLICT, "TASK_PLAN_NOT_ACTIVE");
        }
        Task child = requireChatChild(root, command.taskId(), true);
        PlanStep step = requirePlanStep(plan, child.id(), command.planStepId());
        try {
            PlanStep updated = switch (command.action()) {
                case START -> startStepAfterDependencies(plan, step);
                case COMPLETE -> step.complete(timeProvider.now());
                case FAIL -> step.fail(timeProvider.now());
                case CANCEL -> step.cancel(timeProvider.now());
            };
            Task updatedTask = transitionTask(child, command.action());
            planRepository.updateStep(updated);
            taskRepository.save(updatedTask);
            return toChatExecutionView(plan, updated, updatedTask);
        } catch (IllegalStateException exception) {
            throw new BusinessException(
                    exception.getMessage(), HttpStatus.CONFLICT, "PLAN_STEP_STATE_CONFLICT");
        }
    }

    private Task transitionTask(Task task, PlanStepExecutionAction action) {
        Instant now = timeProvider.now();
        return switch (action) {
            case START -> switch (task.state()) {
                case PENDING -> task.markReady(now).start(now);
                case READY -> task.start(now);
                case BLOCKED -> task.markReady(now).start(now);
                case IN_PROGRESS -> task;
                default -> throw new IllegalStateException(
                        "Task cannot start from " + task.state());
            };
            case COMPLETE -> task.state() == com.spaceagent.platform.project.domain.TaskState.COMPLETED
                    ? task : task.complete(now);
            case FAIL -> switch (task.state()) {
                case PENDING -> task.markReady(now).start(now).fail(now);
                case READY -> task.start(now).fail(now);
                case BLOCKED -> task.markReady(now).start(now).fail(now);
                case IN_PROGRESS -> task.fail(now);
                case FAILED -> task;
                default -> throw new IllegalStateException(
                        "Task cannot fail from " + task.state());
            };
            case CANCEL -> task.state() == com.spaceagent.platform.project.domain.TaskState.CANCELLED
                    ? task : task.cancel(now);
        };
    }

    private Task startRootTask(Task root) {
        Instant now = timeProvider.now();
        return switch (root.state()) {
            case PENDING -> root.markReady(now).start(now);
            case READY -> root.start(now);
            case BLOCKED -> root.markReady(now).start(now);
            case IN_PROGRESS -> root;
            default -> throw new IllegalStateException(
                    "Root Task cannot start from " + root.state());
        };
    }

    private Task completeRootTask(Task root) {
        Instant now = timeProvider.now();
        return switch (root.state()) {
            case PENDING -> root.markReady(now).start(now).complete(now);
            case READY -> root.start(now).complete(now);
            case BLOCKED -> root.markReady(now).start(now).complete(now);
            case IN_PROGRESS -> root.complete(now);
            case COMPLETED -> root;
            default -> throw new IllegalStateException(
                    "Root Task cannot complete from " + root.state());
        };
    }

    private Task failRootTask(Task root) {
        Instant now = timeProvider.now();
        return switch (root.state()) {
            case PENDING -> root.markReady(now).start(now).fail(now);
            case READY -> root.start(now).fail(now);
            case BLOCKED -> root.markReady(now).start(now).fail(now);
            case IN_PROGRESS -> root.fail(now);
            case FAILED -> root;
            default -> throw new IllegalStateException(
                    "Root Task cannot fail from " + root.state());
        };
    }

    private PlanStep startStepAfterDependencies(TaskPlan plan, PlanStep step) {
        Set<String> completed = planRepository.findSteps(plan.id()).stream()
                .filter(value -> value.state() == PlanStepState.COMPLETED)
                .map(PlanStep::id)
                .collect(java.util.stream.Collectors.toSet());
        boolean blocked = planRepository.findDependencies(plan.id()).stream()
                .filter(value -> value.stepId().equals(step.id()))
                .anyMatch(value -> !completed.contains(value.dependsOnStepId()));
        if (blocked) {
            throw new BusinessException(
                    "PlanStep dependencies are not complete",
                    HttpStatus.CONFLICT,
                    "PLAN_STEP_DEPENDENCIES_INCOMPLETE");
        }
        return step.start(timeProvider.now());
    }

    private TaskPlan requirePlanById(String projectId, String planId, boolean forUpdate) {
        requireUuid(planId, "TASK_PLAN_NOT_FOUND");
        return (forUpdate
                ? planRepository.findByIdForUpdate(planId)
                : planRepository.findById(planId))
                .filter(plan -> projectId.equals(plan.projectId()))
                .orElseThrow(() -> new BusinessException(
                        "TaskPlan not found", HttpStatus.NOT_FOUND, "TASK_PLAN_NOT_FOUND"));
    }

    private PlanStep requirePlanStep(TaskPlan plan, String taskId, String planStepId) {
        requireUuid(planStepId, "PLAN_STEP_NOT_FOUND");
        return planRepository.findSteps(plan.id()).stream()
                .filter(step -> planStepId.equals(step.id()))
                .filter(step -> taskId.equals(step.childTaskId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "PlanStep not found for Task",
                        HttpStatus.NOT_FOUND,
                        "PLAN_STEP_NOT_FOUND"));
    }

    private static TaskExecutionReferenceView toExecutionView(
            TaskPlan plan, PlanStep step, Task task) {
        return new TaskExecutionReferenceView(
                plan.projectId(), plan.rootTaskId(), task.id(), plan.id(), step.id(),
                plan.status(), step.state(), task.state());
    }

    private static ChatPlanStepExecutionReferenceView toChatExecutionView(
            TaskPlan plan, PlanStep step, Task task) {
        return new ChatPlanStepExecutionReferenceView(
                plan.conversationId(), plan.rootTaskId(), task.id(), plan.id(), step.id(),
                plan.status(), step.state(), task.state());
    }

    private TaskPlan activate(TaskPlan plan, Task root, Instant now) {
        if (plan.status() == TaskPlanStatus.ACTIVE) {
            if (!plan.id().equals(root.currentTaskPlanId())) {
                throw new BusinessException(
                        "Active TaskPlan is not the Root Task current plan",
                        HttpStatus.CONFLICT,
                        "TASK_PLAN_ACTIVE_CONFLICT");
            }
            return plan;
        }
        boolean anotherActive = planRepository.findByRootTaskId(root.id()).stream()
                .anyMatch(value -> value.status() == TaskPlanStatus.ACTIVE
                        && !value.id().equals(plan.id()));
        if (anotherActive) {
            throw new BusinessException(
                    "Another TaskPlan is already active for the root Task",
                    HttpStatus.CONFLICT,
                    "TASK_PLAN_ACTIVE_CONFLICT");
        }
        TaskPlan activated = plan.activate(now);
        taskRepository.save(root.activatePlan(plan.id(), now));
        return activated;
    }

    private TaskPlan cancel(TaskPlan plan, Task root, Instant now) {
        TaskPlan cancelled = plan.cancel(now);
        if (plan.id().equals(root.currentTaskPlanId())) {
            taskRepository.save(root.clearPlan(now));
        }
        return cancelled;
    }

    private Project requireManagedActiveProject(String tenantId, String userId, String projectId) {
        Project project = accessPolicy.requireProject(tenantId, userId, projectId);
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, userId, ProjectRole::canManageTasks);
        return project;
    }

    private Task requireTask(String projectId, String taskId, boolean forUpdate) {
        requireUuid(taskId, "TASK_NOT_FOUND");
        return (forUpdate ? taskRepository.findByIdForUpdate(taskId) : taskRepository.findById(taskId))
                .filter(task -> projectId.equals(task.projectId()))
                .orElseThrow(() -> new BusinessException(
                        "Task not found", HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
    }

    private Task requireChatRoot(
            String tenantId, String userId, String conversationId,
            String taskId, boolean forUpdate) {
        requireChatScope(tenantId, userId, conversationId);
        requireUuid(taskId, "CHAT_TASK_NOT_FOUND");
        return (forUpdate ? taskRepository.findByIdForUpdate(taskId) : taskRepository.findById(taskId))
                .filter(task -> task.projectId() == null)
                .filter(task -> task.parentTaskId() == null)
                .filter(task -> tenantId.equals(task.tenantId()))
                .filter(task -> userId.equals(task.ownerUserId()))
                .filter(task -> conversationId.equals(task.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Chat Root Task not found", HttpStatus.NOT_FOUND,
                        "CHAT_TASK_NOT_FOUND"));
    }

    private Task requireChatChild(Task root, String taskId, boolean forUpdate) {
        requireUuid(taskId, "CHAT_TASK_NOT_FOUND");
        return (forUpdate ? taskRepository.findByIdForUpdate(taskId) : taskRepository.findById(taskId))
                .filter(task -> task.projectId() == null)
                .filter(task -> root.id().equals(task.parentTaskId()))
                .filter(task -> root.tenantId().equals(task.tenantId()))
                .filter(task -> root.ownerUserId().equals(task.ownerUserId()))
                .filter(task -> root.conversationId().equals(task.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Chat Child Task not found", HttpStatus.NOT_FOUND,
                        "CHAT_TASK_NOT_FOUND"));
    }

    private TaskPlan requireChatPlan(Task root, String planId, boolean forUpdate) {
        requireUuid(planId, "TASK_PLAN_NOT_FOUND");
        return (forUpdate
                ? planRepository.findByIdForUpdate(planId)
                : planRepository.findById(planId))
                .filter(plan -> plan.projectId() == null)
                .filter(plan -> root.id().equals(plan.rootTaskId()))
                .filter(plan -> root.tenantId().equals(plan.tenantId()))
                .filter(plan -> root.ownerUserId().equals(plan.ownerUserId()))
                .filter(plan -> root.conversationId().equals(plan.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Chat TaskPlan not found", HttpStatus.NOT_FOUND,
                        "TASK_PLAN_NOT_FOUND"));
    }

    private TaskPlan requirePlan(
            String projectId,
            String rootTaskId,
            String planId,
            boolean forUpdate) {
        requireUuid(planId, "TASK_PLAN_NOT_FOUND");
        return (forUpdate
                ? planRepository.findByIdForUpdate(planId)
                : planRepository.findById(planId))
                .filter(plan -> projectId.equals(plan.projectId()))
                .filter(plan -> rootTaskId.equals(plan.rootTaskId()))
                .orElseThrow(() -> new BusinessException(
                        "TaskPlan not found", HttpStatus.NOT_FOUND, "TASK_PLAN_NOT_FOUND"));
    }

    private String resolveGeneratedByAgent(
            String tenantId, String userId, String agentId, String versionId) {
        String normalizedAgentId = normalizeOptional(agentId);
        if (normalizedAgentId != null) {
            if (agentApi == null) {
                throw invalidInput("generatedByAgentId validation is unavailable");
            }
            return agentApi.findById(normalizedAgentId)
                    .filter(value -> tenantId.equals(value.tenantId()))
                    .filter(value -> userId.equals(value.ownerId()))
                    .map(value -> value.id())
                    .orElseThrow(() -> invalidInput("generatedByAgentId is not accessible"));
        }
        if (versionId != null && !versionId.isBlank()) {
            throw invalidInput("generatedByConfigurationHash is not accepted from clients; use generatedByAgentId");
        }
        return null;
    }

    private void validatePreferredAgent(String tenantId, String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        if (agentApi == null) {
            throw invalidInput("preferredAgentId validation is unavailable");
        }
        agentApi.findById(agentId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> userId.equals(value.ownerId()))
                .orElseThrow(() -> invalidInput("preferredAgentId is not accessible"));
    }

    private static void validateDraftGraph(List<CreateTaskPlanCommand.PlanStepDraft> drafts) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (CreateTaskPlanCommand.PlanStepDraft draft : drafts) {
            String key = requireText(draft.stepKey(), "stepKey");
            if (graph.putIfAbsent(key, draft.dependsOnStepKeys().stream()
                    .map(value -> requireText(value, "dependsOnStepKey"))
                    .distinct().toList()) != null) {
                throw invalidInput("Duplicate PlanStep key: " + key);
            }
        }
        graph.forEach((key, dependencies) -> dependencies.forEach(dependency -> {
            if (key.equals(dependency)) {
                throw invalidInput("PlanStep cannot depend on itself: " + key);
            }
            if (!graph.containsKey(dependency)) {
                throw invalidInput("Unknown PlanStep dependency: " + dependency);
            }
        }));
        Map<String, Integer> colors = new HashMap<>();
        graph.keySet().forEach(key -> visit(key, graph, colors));
    }

    private static void validateChatProposalGraph(
            List<CreateChatTaskPlanProposalCommand.StepProposal> proposals) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (CreateChatTaskPlanProposalCommand.StepProposal proposal : proposals) {
            String key = requireBoundedText(proposal.stepKey(), "stepKey", 64);
            requireBoundedText(proposal.goal(), "goal", 8_000);
            if (graph.putIfAbsent(key, proposal.dependsOnStepKeys().stream()
                    .map(value -> requireBoundedText(value, "dependsOnStepKey", 64))
                    .distinct().toList()) != null) {
                throw invalidInput("Duplicate PlanStep key: " + key);
            }
        }
        graph.forEach((key, dependencies) -> dependencies.forEach(dependency -> {
            if (key.equals(dependency)) {
                throw invalidInput("PlanStep cannot depend on itself: " + key);
            }
            if (!graph.containsKey(dependency)) {
                throw invalidInput("Unknown PlanStep dependency: " + dependency);
            }
        }));
        Map<String, Integer> colors = new HashMap<>();
        graph.keySet().forEach(key -> visit(key, graph, colors));
    }

    private static String proposalHash(
            String strategy,
            List<CreateChatTaskPlanProposalCommand.StepProposal> proposals) {
        StringBuilder canonical = new StringBuilder(strategy).append('\n');
        proposals.forEach(proposal -> canonical
                .append(proposal.stepKey().trim()).append('\u001f')
                .append(proposal.goal().trim()).append('\u001f')
                .append(String.join("\u001e", proposal.dependsOnStepKeys().stream()
                        .map(String::trim).distinct().sorted().toList()))
                .append('\n'));
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void visit(
            String key,
            Map<String, List<String>> graph,
            Map<String, Integer> colors) {
        int color = colors.getOrDefault(key, 0);
        if (color == 1) {
            throw invalidInput("PlanStep dependency graph contains a cycle");
        }
        if (color == 2) {
            return;
        }
        colors.put(key, 1);
        graph.get(key).forEach(dependency -> visit(dependency, graph, colors));
        colors.put(key, 2);
    }

    private TaskPlanView toView(TaskPlan plan) {
        Map<String, List<String>> dependencies = new HashMap<>();
        planRepository.findDependencies(plan.id()).forEach(dependency ->
                dependencies.computeIfAbsent(dependency.stepId(), ignored -> new ArrayList<>())
                        .add(dependency.dependsOnStepId()));
        List<PlanStepView> steps = planRepository.findSteps(plan.id()).stream()
                .map(step -> new PlanStepView(
                        step.id(), step.stepKey(), step.sequence(), step.childTaskId(),
                        dependencies.getOrDefault(step.id(), List.of()),
                        step.requiredCapability(), step.preferredAgentId(),
                        step.expectedOutput(), step.acceptanceCriteria(),
                        step.approvalRequired(), step.state(), step.createdAt(), step.updatedAt()))
                .toList();
        return new TaskPlanView(
                plan.id(), plan.projectId(), plan.rootTaskId(), plan.versionNumber(),
                plan.status(), plan.generatedByConfigurationHash(), plan.createdBy(),
                plan.approvedBy(), plan.approvedAt(), steps,
                plan.createdAt(), plan.updatedAt(),
                plan.projectId() == null ? "CHAT" : "PROJECT",
                plan.conversationId(), plan.sourceAgentRunId(), plan.proposalHash(),
                plan.strategySummary(), plan.generatedByAgentId(),
                plan.generatedByRunConfigurationSnapshotId());
    }

    private static void requireUuid(String value, String code) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException("Resource not found", HttpStatus.NOT_FOUND, code);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidInput(field + " is required");
        }
        return value.trim();
    }

    private static String requireBoundedText(String value, String field, int maxLength) {
        String normalized = requireText(value, field);
        if (normalized.length() > maxLength) {
            throw invalidInput(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static void requireChatScope(String tenantId, String userId, String conversationId) {
        if (tenantId == null || tenantId.isBlank()
                || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            throw invalidInput("Chat TaskPlan scope is required");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException invalidInput(String message) {
        return new BusinessException(
                message == null ? "Invalid TaskPlan input" : message,
                HttpStatus.BAD_REQUEST,
                "TASK_PLAN_INPUT_INVALID");
    }
}
