package com.spaceagent.platform.integration.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.ListTasksQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.TransitionTaskCommand;
import com.spaceagent.platform.project.api.UpdateTaskCommand;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** HTTP adapter for Project-owned Task foundation use cases. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class PlatformTaskHttpController {

    private final TaskApplicationApi taskApi;

    public PlatformTaskHttpController(TaskApplicationApi taskApi) {
        this.taskApi = taskApi;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskView> createTask(
            @PathVariable String projectId,
            @Valid @RequestBody CreateTaskRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(taskApi.createTask(new CreateTaskCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                request.parentTaskId(),
                request.title(),
                request.goal(),
                request.description(),
                request.constraints(),
                request.acceptanceCriteria())));
    }

    @GetMapping
    public ApiResponse<List<TaskView>> listTasks(
            @PathVariable String projectId,
            Authentication authentication) {
        return ApiResponse.ok(taskApi.listTasks(new ListTasksQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId)));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskView> getTask(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return ApiResponse.ok(taskApi.getTask(new GetTaskQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                taskId)));
    }

    @PatchMapping("/{taskId}")
    public ApiResponse<TaskView> updateTask(
            @PathVariable String projectId,
            @PathVariable String taskId,
            @RequestBody JsonNode request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        if (request == null || !request.isObject()) {
            throw invalidPatch("Task patch must be a JSON object");
        }

        boolean titlePresent = request.has("title");
        boolean goalPresent = request.has("goal");
        boolean descriptionPresent = request.has("description");
        boolean constraintsPresent = request.has("constraints");
        boolean acceptancePresent = request.has("acceptanceCriteria");
        if (!titlePresent && !goalPresent && !descriptionPresent
                && !constraintsPresent && !acceptancePresent) {
            throw invalidPatch("Task patch must contain an intent field");
        }
        String title = textField(request, "title", titlePresent, false);
        String goal = textField(request, "goal", goalPresent, false);
        String description = textField(request, "description", descriptionPresent, true);
        List<String> constraints = stringList(request, "constraints", constraintsPresent);
        List<String> acceptanceCriteria = stringList(
                request, "acceptanceCriteria", acceptancePresent);

        return ApiResponse.ok(taskApi.updateTask(new UpdateTaskCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                taskId,
                title,
                goal,
                description,
                descriptionPresent,
                constraints,
                constraintsPresent,
                acceptanceCriteria,
                acceptancePresent)));
    }

    @PostMapping("/{taskId}/ready")
    public ApiResponse<TaskView> markReady(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.MARK_READY, authentication);
    }

    @PostMapping("/{taskId}/start")
    public ApiResponse<TaskView> start(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.START, authentication);
    }

    @PostMapping("/{taskId}/block")
    public ApiResponse<TaskView> block(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.BLOCK, authentication);
    }

    @PostMapping("/{taskId}/complete")
    public ApiResponse<TaskView> complete(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.COMPLETE, authentication);
    }

    @PostMapping("/{taskId}/fail")
    public ApiResponse<TaskView> fail(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.FAIL, authentication);
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskView> cancel(
            @PathVariable String projectId,
            @PathVariable String taskId,
            Authentication authentication) {
        return transition(projectId, taskId, TaskTransition.CANCEL, authentication);
    }

    private ApiResponse<TaskView> transition(
            String projectId,
            String taskId,
            TaskTransition transition,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(taskApi.transitionTask(new TransitionTaskCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                taskId,
                transition)));
    }

    private static String textField(
            JsonNode request,
            String field,
            boolean present,
            boolean nullable) {
        if (!present) {
            return null;
        }
        JsonNode value = request.get(field);
        if (nullable && value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidPatch("Task " + field + " must be non-blank text"
                    + (nullable ? " or null" : ""));
        }
        return value.asText();
    }

    private static List<String> stringList(
            JsonNode request,
            String field,
            boolean present) {
        if (!present) {
            return null;
        }
        JsonNode value = request.get(field);
        if (!value.isArray()) {
            throw invalidPatch("Task " + field + " must be an array of non-blank strings");
        }
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalidPatch(
                        "Task " + field + " must contain only non-blank strings");
            }
            values.add(item.asText());
        });
        return List.copyOf(values);
    }

    private static BusinessException invalidPatch(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "TASK_PATCH_INVALID");
    }

    public record CreateTaskRequest(
            String parentTaskId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria) {
    }
}
