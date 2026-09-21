package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Owner-scoped, secret-free projection and management boundary for assignment evidence. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/task-plans/{taskPlanId}/steps/{planStepId}/assignment")
public class PlatformProjectPlanStepAssignmentHttpController {
    private final ProjectPlanStepAssignmentApplicationApi assignments;

    public PlatformProjectPlanStepAssignmentHttpController(ProjectPlanStepAssignmentApplicationApi assignments) {
        this.assignments = assignments;
    }

    @GetMapping
    public ApiResponse<ProjectPlanStepAssignmentApplicationApi.AssignmentView> get(
            @PathVariable String projectId, @PathVariable String taskPlanId, @PathVariable String planStepId,
            Authentication authentication) {
        return ApiResponse.ok(assignments.get(new ProjectPlanStepAssignmentApplicationApi.AssignmentQuery(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, taskPlanId, planStepId)));
    }

    @PostMapping("/default") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectPlanStepAssignmentApplicationApi.AssignmentView> defineDefault(
            @PathVariable String projectId, @PathVariable String taskPlanId, @PathVariable String planStepId,
            @Valid @RequestBody AssignmentRequest request, Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(assignments.definePlanDefault(command(projectId, taskPlanId, planStepId, request, authentication)));
    }

    @PostMapping("/override") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectPlanStepAssignmentApplicationApi.AssignmentView> defineOverride(
            @PathVariable String projectId, @PathVariable String taskPlanId, @PathVariable String planStepId,
            @Valid @RequestBody AssignmentRequest request, Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        var c = command(projectId, taskPlanId, planStepId, request, authentication);
        return ApiResponse.ok(assignments.defineStepOverride(new ProjectPlanStepAssignmentApplicationApi.StepOverrideAssignmentCommand(
                c.tenantId(), c.ownerId(), c.projectId(), c.taskPlanId(), c.planStepId(), c.agentId(),
                null, c.reviewerAgentId(), null, c.modelPoolId(),
                null, null)));
    }

    private ProjectPlanStepAssignmentApplicationApi.PlanDefaultAssignmentCommand command(String projectId,
            String taskPlanId, String stepId, AssignmentRequest request, Authentication authentication) {
        return new ProjectPlanStepAssignmentApplicationApi.PlanDefaultAssignmentCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication), projectId,
                taskPlanId, stepId, request.agentId(), null, request.reviewerAgentId(),
                null, request.modelPoolId(), null, null);
    }

    public record AssignmentRequest(
            @NotBlank @Size(max = 36) String agentId,
            @NotBlank @Size(max = 36) String reviewerAgentId,
            @Size(max = 36) String modelPoolId) {}
}
