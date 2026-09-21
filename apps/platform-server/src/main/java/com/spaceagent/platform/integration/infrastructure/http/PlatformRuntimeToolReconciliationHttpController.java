package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime/runs/{agentRunId}/tools")
public class PlatformRuntimeToolReconciliationHttpController {
    private final RuntimeToolReconciliationApplicationApi reconciliation;

    public PlatformRuntimeToolReconciliationHttpController(
            RuntimeToolReconciliationApplicationApi reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/{toolCallId}/reconcile")
    public ApiResponse<RuntimeToolReconciliationApplicationApi.ReconciliationView> reconcile(
            @PathVariable String agentRunId,
            @PathVariable String toolCallId,
            @Valid @RequestBody Request request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(reconciliation.reconcile(
                new RuntimeToolReconciliationApplicationApi.ReconcileCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), agentRunId, toolCallId,
                        request.expectedRevision(), request.reason())));
    }

    public record Request(
            @Min(1) long expectedRevision,
            @NotBlank @Size(max = 1_000) String reason) {
    }
}
