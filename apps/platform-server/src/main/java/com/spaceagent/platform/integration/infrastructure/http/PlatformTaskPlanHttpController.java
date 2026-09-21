package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.GetTaskPlanQuery;
import com.spaceagent.platform.project.api.ListTaskPlansQuery;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{rootTaskId}/plans")
public class PlatformTaskPlanHttpController {

    private final TaskPlanApplicationApi taskPlanApi;

    public PlatformTaskPlanHttpController(TaskPlanApplicationApi taskPlanApi) {
        this.taskPlanApi = taskPlanApi;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskPlanView> createPlan(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @Valid @RequestBody CreateTaskPlanRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(taskPlanApi.createPlan(new CreateTaskPlanCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                rootTaskId,
                null,
                request.steps().stream().map(step -> new CreateTaskPlanCommand.PlanStepDraft(
                        step.stepKey(), step.childTaskId(), step.dependsOnStepKeys(),
                        step.requiredCapability(), step.preferredAgentId(),
                        step.expectedOutput(), step.acceptanceCriteria(),
                        step.approvalRequired())).toList(),
                request.generatedByAgentId(),
                request.generatedByRunConfigurationSnapshotId())));
    }

    @GetMapping
    public ApiResponse<List<TaskPlanView>> listPlans(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            Authentication authentication) {
        return ApiResponse.ok(taskPlanApi.listPlans(new ListTaskPlansQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, rootTaskId)));
    }

    @GetMapping("/{planId}")
    public ApiResponse<TaskPlanView> getPlan(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return ApiResponse.ok(taskPlanApi.getPlan(new GetTaskPlanQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId, rootTaskId, planId)));
    }

    @PostMapping("/{planId}/propose")
    public ApiResponse<TaskPlanView> propose(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(projectId, rootTaskId, planId, TaskPlanAction.PROPOSE, authentication);
    }

    @PostMapping("/{planId}/approve")
    public ApiResponse<TaskPlanView> approve(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(projectId, rootTaskId, planId, TaskPlanAction.APPROVE, authentication);
    }

    @PostMapping("/{planId}/activate")
    public ApiResponse<TaskPlanView> activate(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(projectId, rootTaskId, planId, TaskPlanAction.ACTIVATE, authentication);
    }

    @PostMapping("/{planId}/complete")
    public ApiResponse<TaskPlanView> complete(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(projectId, rootTaskId, planId, TaskPlanAction.COMPLETE, authentication);
    }

    @PostMapping("/{planId}/cancel")
    public ApiResponse<TaskPlanView> cancel(
            @PathVariable String projectId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(projectId, rootTaskId, planId, TaskPlanAction.CANCEL, authentication);
    }

    private ApiResponse<TaskPlanView> transition(
            String projectId,
            String rootTaskId,
            String planId,
            TaskPlanAction action,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(taskPlanApi.transition(new TaskPlanActionCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId, rootTaskId, planId, action)));
    }

    public record CreateTaskPlanRequest(
            String generatedByAgentId,
            String generatedByRunConfigurationSnapshotId,
            @Size(min = 1, max = 100) List<@Valid PlanStepRequest> steps) {

        public CreateTaskPlanRequest {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record PlanStepRequest(
            @NotBlank @Size(max = 64) String stepKey,
            @NotBlank String childTaskId,
            List<@NotBlank @Size(max = 64) String> dependsOnStepKeys,
            @Size(max = 120) String requiredCapability,
            String preferredAgentId,
            @NotBlank String expectedOutput,
            List<@NotBlank String> acceptanceCriteria,
            boolean approvalRequired) {

        public PlanStepRequest {
            dependsOnStepKeys = dependsOnStepKeys == null
                    ? List.of() : List.copyOf(dependsOnStepKeys);
            acceptanceCriteria = acceptanceCriteria == null
                    ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }
}
