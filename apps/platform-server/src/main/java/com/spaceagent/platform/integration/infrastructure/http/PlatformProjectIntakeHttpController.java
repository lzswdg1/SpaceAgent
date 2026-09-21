package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.application.ProjectIntakeCoordinator;
import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
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
@RequestMapping("/api/v1/projects/{projectId}/directories/{directoryId}/intakes")
public class PlatformProjectIntakeHttpController {
    private final ProjectIntakeApplicationApi intake;
    private final ProjectIntakeCoordinator coordinator;

    public PlatformProjectIntakeHttpController(
            ProjectIntakeApplicationApi intake,
            ProjectIntakeCoordinator coordinator) {
        this.intake = intake;
        this.coordinator = coordinator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectIntakeApplicationApi.JobView> enqueue(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(min = 8, max = 200) String idempotencyKey,
            @Valid @RequestBody EnqueueRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.enqueue(new ProjectIntakeApplicationApi.EnqueueCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId,
                request.conversationId(), request.agentId(),
                request.goal(), idempotencyKey)));
    }

    @GetMapping
    public ApiResponse<ProjectIntakeApplicationApi.JobPageView> list(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            Authentication authentication) {
        return ApiResponse.ok(intake.list(new ProjectIntakeApplicationApi.ListQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId,
                page, pageSize)));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<ProjectIntakeApplicationApi.JobView> get(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @PathVariable String jobId,
            Authentication authentication) {
        return ApiResponse.ok(intake.get(new ProjectIntakeApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId, jobId)));
    }

    @PostMapping("/{jobId}/confirm")
    public ApiResponse<ProjectIntakeApplicationApi.JobView> confirm(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @PathVariable String jobId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.confirm(new ProjectIntakeApplicationApi.ConfirmCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId, jobId,
                request.proposalHash())));
    }

    @PostMapping("/{jobId}/reject")
    public ApiResponse<ProjectIntakeApplicationApi.JobView> reject(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @PathVariable String jobId,
            @Valid @RequestBody RejectRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(coordinator.reject(new ProjectIntakeApplicationApi.RejectCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId, jobId,
                request.proposalHash(), request.reason())));
    }

    public record EnqueueRequest(
            @NotBlank @Size(max = 80) String conversationId,
            @NotBlank @Size(max = 80) String agentId,
            @NotBlank @Size(max = 8_000) String goal) {
    }

    public record ReviewRequest(
            @NotBlank @Size(min = 71, max = 71) String proposalHash) {
    }

    public record RejectRequest(
            @NotBlank @Size(min = 71, max = 71) String proposalHash,
            @NotBlank @Size(max = 500) String reason) {
    }
}
