package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/multi-agent")
public class PlatformMultiAgentCollaborationHttpController {

    private final MultiAgentCollaborationApplicationApi api;

    public PlatformMultiAgentCollaborationHttpController(MultiAgentCollaborationApplicationApi api) {
        this.api = api;
    }

    @PostMapping("/runs/{runId}/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MultiAgentCollaborationApplicationApi.DelegationView> delegate(
            @PathVariable String runId,
            @Valid @RequestBody DelegateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.delegate(new MultiAgentCollaborationApplicationApi.DelegateCommand(
                PlatformHttpSupport.userId(authentication),
                runId,
                request.targetAgentId(),
                request.sourceRepositoryId(),
                request.baseRef())));
    }

    @GetMapping("/runs/{runId}/delegations")
    public ApiResponse<List<MultiAgentCollaborationApplicationApi.DelegationView>> delegations(
            @PathVariable String runId,
            Authentication authentication) {
        return ApiResponse.ok(api.delegations(PlatformHttpSupport.userId(authentication), runId));
    }

    @PostMapping("/runs/{runId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MultiAgentCollaborationApplicationApi.ReviewView> review(
            @PathVariable String runId,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.requestReview(new MultiAgentCollaborationApplicationApi.ReviewCommand(
                PlatformHttpSupport.userId(authentication),
                runId,
                request.childRunId(),
                request.reviewerAgentId(),
                request.artifactIds())));
    }

    @PostMapping("/reviews/{reviewId}/decision")
    public ApiResponse<MultiAgentCollaborationApplicationApi.ReviewView> decide(
            @PathVariable String reviewId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.decide(new MultiAgentCollaborationApplicationApi.DecideReviewCommand(
                PlatformHttpSupport.userId(authentication),
                reviewId,
                request.decision(),
                request.evidence())));
    }

    public record DelegateRequest(
            @NotBlank String targetAgentId,
            @NotBlank String sourceRepositoryId,
            String baseRef) {}

    public record ReviewRequest(
            @NotBlank String childRunId,
            @NotBlank String reviewerAgentId,
            @NotEmpty List<@NotBlank String> artifactIds) {}

    public record DecisionRequest(
            @NotNull AgentReviewDecision decision,
            @NotBlank String evidence) {}
}
