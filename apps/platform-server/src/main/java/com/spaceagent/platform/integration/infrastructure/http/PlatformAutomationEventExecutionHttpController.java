package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.automation.api.AutomationEventExecutionApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/automation-events")
public class PlatformAutomationEventExecutionHttpController {
    private final AutomationEventExecutionApplicationApi executions;

    public PlatformAutomationEventExecutionHttpController(
            AutomationEventExecutionApplicationApi executions) {
        this.executions = executions;
    }

    @PostMapping("/{occurrenceId}/resume-approval")
    public ApiResponse<AutomationEventExecutionApplicationApi.DispatchView> resumeApproval(
            @PathVariable String occurrenceId,
            @Valid @RequestBody ResumeApprovalRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(executions.resumeApproval(
                new AutomationEventExecutionApplicationApi.ResumeApprovalCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), occurrenceId,
                        request.approvalId(), request.expectedOccurrenceRevision())));
    }

    public record ResumeApprovalRequest(
            @NotBlank String approvalId,
            @Min(1) long expectedOccurrenceRevision) {}
}
