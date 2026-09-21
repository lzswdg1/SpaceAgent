package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.ListChatTasksQuery;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.ListTasksQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.TransitionTaskCommand;
import com.spaceagent.platform.project.api.TransitionChatTaskCommand;
import com.spaceagent.platform.project.api.UpdateTaskCommand;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.Task;
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
import java.util.List;
import java.util.UUID;

/** Application coordinator for Project-owned Task intent and lifecycle. */
@Service
public class TaskApplicationService implements TaskApplicationApi {

    private final TaskRepository taskRepository;
    private final ProjectAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public TaskApplicationService(
            TaskRepository taskRepository,
            ProjectAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.taskRepository = taskRepository;
        this.accessPolicy = accessPolicy;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional
    public TaskView createTask(CreateTaskCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, command.userId(), ProjectRole::canManageTasks);

        String parentTaskId = normalizeOptional(command.parentTaskId());
        if (parentTaskId != null) {
            requireTask(project.id(), parentTaskId, false);
        }
        Instant now = timeProvider.now();
        Task task = createDomainTask(command, project.id(), parentTaskId, now);
        save(task);
        return toView(task);
    }

    @Override
    @Transactional
    public TaskView createOrGetChatRootTask(CreateChatRootTaskCommand command) {
        requireChatScope(command.tenantId(), command.userId(), command.conversationId());
        if (command.sourceMessageId() == null || command.sourceMessageId().isBlank()) {
            throw invalidInput("sourceMessageId is required");
        }
        if (command.goal() == null || command.goal().isBlank()) {
            throw invalidInput("Chat Task goal is required");
        }
        Task existing = taskRepository.findBySourceMessageId(command.sourceMessageId()).orElse(null);
        if (existing != null) return chatView(existing, command.tenantId(), command.userId(),
                command.conversationId(), command.goal());
        Instant now = timeProvider.now();
        Task proposed;
        try {
            proposed = Task.createChatRoot(
                    idGenerator.nextId(), command.tenantId(), command.userId(),
                    command.conversationId(), command.sourceMessageId(), command.title(),
                    command.goal(), List.of("Address the user request with a complete response"), now);
            Task persisted = taskRepository.createOrFindChatRoot(proposed);
            return chatView(persisted, command.tenantId(), command.userId(),
                    command.conversationId(), command.goal());
        } catch (IllegalArgumentException error) {
            throw invalidInput(error.getMessage());
        }
    }

    @Override
    public TaskView getChatTask(GetChatTaskQuery query) {
        requireChatScope(query.tenantId(), query.userId(), query.conversationId());
        return toView(requireChatTask(
                query.taskId(), query.tenantId(), query.userId(), query.conversationId(), false));
    }

    @Override
    public List<TaskView> listChatTasks(ListChatTasksQuery query) {
        requireChatScope(query.tenantId(), query.userId(), query.conversationId());
        int limit = query.limit() <= 0 ? 100 : Math.min(query.limit(), 200);
        return taskRepository.findRootsByConversationId(query.conversationId(), limit).stream()
                .filter(task -> query.tenantId().equals(task.tenantId()))
                .filter(task -> query.userId().equals(task.ownerUserId()))
                .filter(task -> task.parentTaskId() == null)
                .map(TaskApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional
    public TaskView transitionChatTask(TransitionChatTaskCommand command) {
        requireChatScope(command.tenantId(), command.userId(), command.conversationId());
        if (command.transition() != TaskTransition.COMPLETE
                && command.transition() != TaskTransition.FAIL
                && command.transition() != TaskTransition.CANCEL) {
            throw invalidInput("Chat Task transition must be terminal");
        }
        Task current = requireChatTask(
                command.taskId(), command.tenantId(), command.userId(),
                command.conversationId(), true);
        TaskState desired = switch (command.transition()) {
            case COMPLETE -> TaskState.COMPLETED;
            case FAIL -> TaskState.FAILED;
            case CANCEL -> TaskState.CANCELLED;
            default -> throw invalidInput("Unsupported Chat Task transition");
        };
        if (current.state() == desired) return toView(current);
        if (current.state().isTerminal()) {
            throw stateConflict("Chat Task is already terminal: " + current.state());
        }
        Task updated = switch (command.transition()) {
            case COMPLETE -> current.complete(timeProvider.now());
            case FAIL -> current.fail(timeProvider.now());
            case CANCEL -> current.cancel(timeProvider.now());
            default -> throw invalidInput("Unsupported Chat Task transition");
        };
        save(updated);
        return toView(updated);
    }

    @Override
    public TaskView getTask(GetTaskQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        return toView(requireTask(project.id(), query.taskId(), false));
    }

    @Override
    public List<TaskView> listTasks(ListTasksQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        return taskRepository.findByProjectId(project.id()).stream()
                .map(TaskApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional
    public TaskView updateTask(UpdateTaskCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, command.userId(), ProjectRole::canManageTasks);
        Task current = requireTask(project.id(), command.taskId(), true);

        String title = command.title() == null ? current.title() : command.title();
        String goal = command.goal() == null ? current.goal() : command.goal();
        String description = command.descriptionPresent()
                ? command.description()
                : current.description();
        if (command.constraintsPresent() && command.constraints() == null) {
            throw invalidInput("constraints must not be null when present");
        }
        if (command.acceptanceCriteriaPresent() && command.acceptanceCriteria() == null) {
            throw invalidInput("acceptanceCriteria must not be null when present");
        }
        List<String> constraints = command.constraintsPresent()
                ? command.constraints()
                : current.constraints();
        List<String> acceptanceCriteria = command.acceptanceCriteriaPresent()
                ? command.acceptanceCriteria()
                : current.acceptanceCriteria();
        try {
            Task updated = current.updateIntent(
                    title, goal, description, constraints, acceptanceCriteria, timeProvider.now());
            save(updated);
            return toView(updated);
        } catch (IllegalArgumentException exception) {
            throw invalidInput(exception.getMessage());
        } catch (IllegalStateException exception) {
            throw stateConflict(exception.getMessage());
        }
    }

    @Override
    @Transactional
    public TaskView transitionTask(TransitionTaskCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        TaskTransition transition = requireTransition(command.transition());
        if (transition == TaskTransition.MARK_READY || transition == TaskTransition.CANCEL) {
            accessPolicy.requireRole(project, command.userId(), ProjectRole::canManageTasks);
        } else {
            accessPolicy.requireRole(project, command.userId(), ProjectRole::canWorkOnTasks);
        }

        Task current = requireTask(project.id(), command.taskId(), true);
        try {
            Task updated = applyTransition(current, transition, timeProvider.now());
            save(updated);
            return toView(updated);
        } catch (IllegalStateException exception) {
            throw stateConflict(exception.getMessage());
        }
    }

    private Task createDomainTask(
            CreateTaskCommand command,
            String projectId,
            String parentTaskId,
            Instant now) {
        try {
            return Task.createProject(
                    idGenerator.nextId(), projectId, command.tenantId(), command.userId(), parentTaskId,
                    command.title(), command.goal(), command.description(),
                    defaultList(command.constraints()),
                    defaultList(command.acceptanceCriteria()),
                    now);
        } catch (IllegalArgumentException exception) {
            throw invalidInput(exception.getMessage());
        }
    }

    private Task requireChatTask(
            String taskId, String tenantId, String userId,
            String conversationId, boolean forUpdate) {
        requireUuid(taskId);
        return (forUpdate ? taskRepository.findByIdForUpdate(taskId) : taskRepository.findById(taskId))
                .filter(task -> task.projectId() == null)
                .filter(task -> tenantId.equals(task.tenantId()))
                .filter(task -> userId.equals(task.ownerUserId()))
                .filter(task -> conversationId.equals(task.conversationId()))
                .orElseThrow(TaskApplicationService::taskNotFound);
    }

    private TaskView chatView(
            Task task, String tenantId, String userId,
            String conversationId, String goal) {
        Task current = requireChatTask(task.id(), tenantId, userId, conversationId, false);
        if (!current.goal().equals(goal.trim())) {
            throw new BusinessException(
                    "Chat source Message is already bound to another Task goal",
                    HttpStatus.CONFLICT, "CHAT_TASK_IDEMPOTENCY_CONFLICT");
        }
        return toView(current);
    }

    private static void requireChatScope(String tenantId, String userId, String conversationId) {
        if (tenantId == null || tenantId.isBlank()
                || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            throw invalidInput("Chat Task scope is required");
        }
    }

    private Task requireTask(String projectId, String taskId, boolean forUpdate) {
        requireUuid(taskId);
        return (forUpdate
                ? taskRepository.findByIdForUpdate(taskId)
                : taskRepository.findById(taskId))
                .filter(task -> projectId.equals(task.projectId()))
                .orElseThrow(TaskApplicationService::taskNotFound);
    }

    private void save(Task task) {
        try {
            taskRepository.save(task);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                    "Task persistence constraint violated",
                    HttpStatus.CONFLICT,
                    "TASK_PERSISTENCE_CONFLICT");
        }
    }

    private static Task applyTransition(Task task, TaskTransition transition, Instant now) {
        return switch (transition) {
            case MARK_READY -> task.markReady(now);
            case START -> task.start(now);
            case BLOCK -> task.block(now);
            case COMPLETE -> task.complete(now);
            case FAIL -> task.fail(now);
            case CANCEL -> task.cancel(now);
        };
    }

    private static TaskTransition requireTransition(TaskTransition transition) {
        if (transition == null) {
            throw invalidInput("Task transition is required");
        }
        return transition;
    }

    private static void requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw taskNotFound();
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw taskNotFound();
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static TaskView toView(Task task) {
        return new TaskView(
                task.id(), task.projectId(), task.parentTaskId(), task.title(), task.goal(),
                task.description(), task.constraints(), task.acceptanceCriteria(),
                task.currentTaskPlanId(), task.state(),
                task.createdAt(), task.updatedAt(),
                task.projectId() == null ? "CHAT" : "PROJECT",
                task.conversationId(), task.sourceMessageId());
    }

    private static BusinessException taskNotFound() {
        return new BusinessException(
                "Task not found", HttpStatus.NOT_FOUND, "TASK_NOT_FOUND");
    }

    private static BusinessException invalidInput(String message) {
        return new BusinessException(
                message == null ? "Invalid Task input" : message,
                HttpStatus.BAD_REQUEST,
                "TASK_INPUT_INVALID");
    }

    private static BusinessException stateConflict(String message) {
        return new BusinessException(
                message == null ? "Invalid Task state transition" : message,
                HttpStatus.CONFLICT,
                "TASK_STATE_CONFLICT");
    }
}
