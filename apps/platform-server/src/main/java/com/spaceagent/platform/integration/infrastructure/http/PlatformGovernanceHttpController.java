package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/governance")
public class PlatformGovernanceHttpController {

    private final GovernanceApplicationApi api;

    public PlatformGovernanceHttpController(GovernanceApplicationApi api) {
        this.api = api;
    }

    @GetMapping("/policy")
    public ApiResponse<GovernanceApplicationApi.PolicyView> policy(
            Authentication authentication) {
        return ApiResponse.ok(api.getPolicy(actor(authentication)));
    }

    @PutMapping("/policy")
    public ApiResponse<GovernanceApplicationApi.PolicyView> updatePolicy(
            @Valid @RequestBody UpdatePolicyRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.updatePolicy(
                new GovernanceApplicationApi.UpdatePolicyCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication),
                        request.requireCodingFileApproval(),
                        request.requireCommandApproval(),
                        request.requireAutomationApproval(),
                        request.requireNetworkApproval(),
                        request.requireSourceMergeApproval(),
                        request.separationOfDuties(),
                        request.approvalTtlSeconds(),
                        request.expectedRevision())));
    }

    @GetMapping("/approvals")
    public ApiResponse<List<GovernanceApplicationApi.ApprovalView>> approvals(
            @RequestParam(required = false) ApprovalState state,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        return ApiResponse.ok(api.listApprovals(
                new GovernanceApplicationApi.ListApprovalsQuery(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), state, limit)));
    }

    @GetMapping("/approvals/{approvalId}")
    public ApiResponse<GovernanceApplicationApi.ApprovalView> approval(
            @PathVariable UUID approvalId,
            Authentication authentication) {
        return ApiResponse.ok(api.getApproval(
                new GovernanceApplicationApi.ApprovalQuery(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), approvalId.toString())));
    }

    @PostMapping("/approvals/{approvalId}/decision")
    public ApiResponse<GovernanceApplicationApi.ApprovalView> decide(
            @PathVariable UUID approvalId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.decide(new GovernanceApplicationApi.DecisionCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), approvalId.toString(),
                request.decision(), request.note())));
    }

    private GovernanceApplicationApi.ActorQuery actor(Authentication authentication) {
        return new GovernanceApplicationApi.ActorQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication));
    }

    public record UpdatePolicyRequest(
            boolean requireCodingFileApproval,
            boolean requireCommandApproval,
            boolean requireAutomationApproval,
            boolean requireNetworkApproval,
            boolean requireSourceMergeApproval,
            boolean separationOfDuties,
            @Min(60) @Max(604_800) int approvalTtlSeconds,
            @Min(0) long expectedRevision) {}

    public record DecisionRequest(
            @NotNull ApprovalState decision,
            @Size(max = 2_000) String note) {}
}
