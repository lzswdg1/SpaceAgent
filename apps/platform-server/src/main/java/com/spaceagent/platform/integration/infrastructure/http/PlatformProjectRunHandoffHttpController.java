package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.application.ProjectRunHandoffCoordinator;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
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
import org.springframework.validation.annotation.Validated;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class PlatformProjectRunHandoffHttpController {
    private final ProjectRunHandoffCoordinator coordinator;

    public PlatformProjectRunHandoffHttpController(ProjectRunHandoffCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/task-plans/{taskPlanId}/steps/{planStepId}/coding-jobs/{jobId}/handoffs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectRunHandoffCoordinator.Result> create(
            @PathVariable String projectId,
            @PathVariable String taskPlanId,
            @PathVariable String planStepId,
            @PathVariable String jobId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 200) String idempotencyKey,
            @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.create(new ProjectRunHandoffCoordinator.CreateCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, taskPlanId, planStepId,
                jobId, request.targetConversationId(), request.targetAgentId(),
                request.reviewerAgentId(), idempotencyKey)));
    }

    @GetMapping("/run-handoffs/{handoffId}")
    public ApiResponse<ProjectRunHandoffApplicationApi.HandoffView> get(
            @PathVariable String projectId,
            @PathVariable String handoffId,
            Authentication authentication) {
        return ApiResponse.ok(coordinator.get(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, handoffId));
    }

    @GetMapping("/run-handoffs")
    public ApiResponse<ProjectRunHandoffApplicationApi.HandoffPage> list(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        return ApiResponse.ok(coordinator.list(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, page, pageSize));
    }

    public record CreateRequest(
            @NotBlank String targetConversationId,
            @NotBlank String targetAgentId,
            @NotBlank String reviewerAgentId) {}
}
