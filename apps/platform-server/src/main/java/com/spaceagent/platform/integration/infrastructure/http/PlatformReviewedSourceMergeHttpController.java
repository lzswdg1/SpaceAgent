package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.api.ReviewedSourceMergeApplicationApi;
import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/source-merges")
public class PlatformReviewedSourceMergeHttpController {
    private final ReviewedSourceMergeApplicationApi merges;

    public PlatformReviewedSourceMergeHttpController(ReviewedSourceMergeApplicationApi merges) {
        this.merges = merges;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceMergeApplicationApi.SourceMergeView> prepare(
            @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PrepareRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(merges.prepare(new ReviewedSourceMergeApplicationApi.PrepareCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId,
                request.reviewId(), request.commitProposalArtifactId(), idempotencyKey)));
    }

    @GetMapping("/{mergeId}")
    public ApiResponse<SourceMergeApplicationApi.SourceMergeView> get(
            @PathVariable String projectId,
            @PathVariable String mergeId,
            Authentication authentication) {
        return ApiResponse.ok(merges.get(query(projectId, mergeId, authentication)));
    }

    @PostMapping("/{mergeId}/apply")
    public ApiResponse<SourceMergeApplicationApi.SourceMergeView> apply(
            @PathVariable String projectId,
            @PathVariable String mergeId,
            @RequestBody(required = false) ApprovalRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(merges.apply(new ReviewedSourceMergeApplicationApi.ApplyCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, mergeId,
                request == null ? null : request.approvalId())));
    }

    @PostMapping("/{mergeId}/rollback")
    public ApiResponse<SourceMergeApplicationApi.SourceMergeView> rollback(
            @PathVariable String projectId,
            @PathVariable String mergeId,
            @RequestBody(required = false) ApprovalRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(merges.rollback(new ReviewedSourceMergeApplicationApi.RollbackCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, mergeId,
                request == null ? null : request.approvalId())));
    }

    @PostMapping("/{mergeId}/reconcile")
    public ApiResponse<SourceMergeApplicationApi.SourceMergeView> reconcile(
            @PathVariable String projectId,
            @PathVariable String mergeId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(merges.reconcile(query(projectId, mergeId, authentication)));
    }

    private static ReviewedSourceMergeApplicationApi.Query query(
            String projectId, String mergeId, Authentication authentication) {
        return new ReviewedSourceMergeApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, mergeId);
    }

    public record PrepareRequest(
            @NotBlank String reviewId,
            @NotBlank String commitProposalArtifactId) {}

    public record ApprovalRequest(String approvalId) {}
}
