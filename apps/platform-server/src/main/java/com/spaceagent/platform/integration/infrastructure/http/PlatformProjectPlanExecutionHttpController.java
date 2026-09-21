package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{rootTaskId}/plans/{planId}")
public class PlatformProjectPlanExecutionHttpController {
    private final ProjectPlanExecutionApplicationApi execution;

    public PlatformProjectPlanExecutionHttpController(
            ProjectPlanExecutionApplicationApi execution) {
        this.execution = execution;
    }

    @PostMapping("/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectPlanExecutionApplicationApi.DispatchView> execute(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            @Valid @RequestBody ExecutePlanRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(execution.dispatch(
                new ProjectPlanExecutionApplicationApi.DispatchCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication),
                        projectId, rootTaskId, planId,
                        request.projectDirectoryId(), request.conversationId(),
                        request.sourceRepositoryId(), request.agentId(),
                        null, null, request.baseRef(), request.reviewerAgentId())));
    }

    public record ExecutePlanRequest(
            @NotBlank @Size(max = 36) String projectDirectoryId,
            @NotBlank @Size(max = 36) String conversationId,
            @NotBlank @Size(max = 36) String sourceRepositoryId,
            @NotBlank @Size(max = 36) String agentId,
            @NotBlank @Size(max = 36) String reviewerAgentId,
            @NotBlank @Size(max = 240) String baseRef) { }
}
