package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs")
public class PlatformProjectCodingHttpController {
    private final ProjectCodingCoordinator coordinator;
    public PlatformProjectCodingHttpController(ProjectCodingCoordinator coordinator){
        this.coordinator=coordinator;
    }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectCodingJobApplicationApi.JobView> enqueue(
            @PathVariable String projectId,@PathVariable String planId,@PathVariable String stepId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min=8,max=200) String idempotency,
            @Valid @RequestBody EnqueueRequest request,Authentication authentication){
        PlatformHttpSupport.requireWrite(authentication);String tenant=PlatformHttpSupport.tenantId(authentication),user=PlatformHttpSupport.userId(authentication);
        return ApiResponse.ok(coordinator.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                tenant,user,projectId,request.projectDirectoryId(),request.conversationId(),
                request.sourceRepositoryId(),request.rootTaskId(),request.taskId(),planId,null,stepId,
                request.agentId(),null,null,request.baseRef(),idempotency,request.reviewerAgentId())));
    }
    @GetMapping("/{jobId}") public ApiResponse<ProjectCodingJobApplicationApi.JobView> get(
            @PathVariable String projectId,@PathVariable String planId,@PathVariable String stepId,
            @PathVariable String jobId,Authentication authentication){return ApiResponse.ok(coordinator.get(
                    new ProjectCodingJobApplicationApi.Query(PlatformHttpSupport.tenantId(authentication),
                            PlatformHttpSupport.userId(authentication),projectId,planId,stepId,jobId)));}
    @GetMapping public ApiResponse<ProjectCodingJobApplicationApi.JobPage> list(
            @PathVariable String projectId,@PathVariable String planId,@PathVariable String stepId,
            @RequestParam(defaultValue="1") @Min(1) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,
            Authentication authentication){return ApiResponse.ok(coordinator.list(new ProjectCodingJobApplicationApi.ListQuery(
                    PlatformHttpSupport.tenantId(authentication),PlatformHttpSupport.userId(authentication),
                    projectId,planId,stepId,page,pageSize)));}
    @PostMapping("/{jobId}/resume") public ApiResponse<ProjectCodingJobApplicationApi.JobView> resume(
            @PathVariable String projectId,@PathVariable String planId,@PathVariable String stepId,
            @PathVariable String jobId,@Valid @RequestBody ResumeRequest request,
            Authentication authentication){PlatformHttpSupport.requireWrite(authentication);return ApiResponse.ok(coordinator.resume(
                    new ProjectCodingJobApplicationApi.ResumeCommand(PlatformHttpSupport.tenantId(authentication),
                            PlatformHttpSupport.userId(authentication),projectId,planId,stepId,jobId,
                            request.approvalId())));}
    public record EnqueueRequest(
            @NotBlank @Size(max=36) String projectDirectoryId,
            @NotBlank @Size(max=36) String conversationId,
            @NotBlank @Size(max=36) String sourceRepositoryId,
            @NotBlank @Size(max=36) String rootTaskId,
            @NotBlank @Size(max=36) String taskId,
            @NotBlank @Size(max=36) String agentId,
            @NotBlank @Size(max=36) String reviewerAgentId,
            @NotBlank @Size(max=240) String baseRef){}
    public record ResumeRequest(@NotBlank @Size(max=36) String approvalId){}
}
