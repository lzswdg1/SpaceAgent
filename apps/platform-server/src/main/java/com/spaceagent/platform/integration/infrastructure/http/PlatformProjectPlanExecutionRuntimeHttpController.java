package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/task-plans/{taskPlanId}/executions")
public class PlatformProjectPlanExecutionRuntimeHttpController {
    private final ProjectPlanExecutionApplicationApi executions;
    private final ProjectCodingCoordinator coordinator;

    public PlatformProjectPlanExecutionRuntimeHttpController(
            ProjectPlanExecutionApplicationApi executions,
            ProjectCodingCoordinator coordinator) {
        this.executions = executions;
        this.coordinator = coordinator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionView> start(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200)
            String idempotencyKey,
            @Valid @RequestBody StartRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        String tenantId = PlatformHttpSupport.tenantId(authentication);
        String ownerId = PlatformHttpSupport.userId(authentication);
        var dispatched = coordinator.dispatch(
                new com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi
                        .DispatchCommand(
                        tenantId, ownerId, projectId, request.rootTaskId(), taskPlanId,
                        request.projectDirectoryId(), request.conversationId(),
                        request.sourceRepositoryId(), request.agentId(), null,
                        null, request.baseRef(), request.reviewerAgentId()));
        return ApiResponse.ok(executions.get(new ProjectPlanExecutionApplicationApi.Query(
                tenantId, ownerId, projectId, taskPlanId, dispatched.executionId())));
    }

    @GetMapping("/{executionId}")
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionView> get(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            Authentication authentication) {
        return ApiResponse.ok(executions.get(new ProjectPlanExecutionApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, executionId)));
    }

    @GetMapping
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionPage> list(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            Authentication authentication) {
        return ApiResponse.ok(executions.list(new ProjectPlanExecutionApplicationApi.ListQuery(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, page, pageSize)));
    }

    @GetMapping("/{executionId}/control")
    public ApiResponse<ProjectPlanExecutionApplicationApi.ControlView> control(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            Authentication authentication) {
        return ApiResponse.ok(executions.getControl(new ProjectPlanExecutionApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, executionId)));
    }

    @GetMapping("/{executionId}/trace")
    public ApiResponse<ProjectPlanExecutionApplicationApi.ControlView> trace(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            Authentication authentication) {
        return control(projectId, taskPlanId, executionId, authentication);
    }

    @PostMapping("/{executionId}/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionView> pause(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            @Valid @RequestBody PauseRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.pause(new ProjectPlanExecutionApplicationApi.PauseCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, executionId, request.expectedRevision(), request.reason())));
    }

    @PostMapping("/{executionId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionView> resume(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            @Valid @RequestBody RevisionRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.resume(new ProjectPlanExecutionApplicationApi.ResumeCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, executionId, request.expectedRevision())));
    }

    @PostMapping("/{executionId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectPlanExecutionApplicationApi.ExecutionView> cancel(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String executionId,
            @Valid @RequestBody CancelRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.cancel(new ProjectPlanExecutionApplicationApi.CancelCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, executionId, request.expectedRevision(), request.reason())));
    }

    public record StartRequest(
            @NotBlank @Size(max = 36) String projectDirectoryId,
            @NotBlank @Size(max = 36) String conversationId,
            @NotBlank @Size(max = 36) String sourceRepositoryId,
            @NotBlank @Size(max = 36) String rootTaskId,
            @NotBlank @Size(max = 36) String agentId,
            @NotBlank @Size(max = 36) String reviewerAgentId,
            @NotBlank @Size(max = 240) String baseRef) {
    }

    public record RevisionRequest(@Min(1) long expectedRevision) {}

    public record PauseRequest(
            @Min(1) long expectedRevision,
            @NotBlank @Size(max = 240) String reason) {}

    public record CancelRequest(
            @Min(1) long expectedRevision,
            @NotBlank @Size(max = 240) String reason) {}
}
