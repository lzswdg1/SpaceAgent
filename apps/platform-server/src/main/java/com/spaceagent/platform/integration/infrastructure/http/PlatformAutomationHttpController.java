package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/scheduled-tasks")
public class PlatformAutomationHttpController {

    private final AutomationApplicationApi automation;

    public PlatformAutomationHttpController(AutomationApplicationApi automation) {
        this.automation = automation;
    }

    @GetMapping
    public ApiResponse<List<AutomationApplicationApi.ScheduledTaskView>> list(
            @PathVariable String agentId, Authentication authentication) {
        return ApiResponse.ok(automation.list(
                tenant(authentication), user(authentication), agentId));
    }

    @GetMapping("/{scheduleId}")
    public ApiResponse<AutomationApplicationApi.ScheduledTaskView> get(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            Authentication authentication) {
        return ApiResponse.ok(automation.get(
                tenant(authentication), user(authentication), agentId, scheduleId.toString()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AutomationApplicationApi.ScheduledTaskView> create(
            @PathVariable String agentId,
            @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        if (request.agentId() != null && !agentId.equals(request.agentId())) {
            throw new IllegalArgumentException("Body agentId must match path agentId");
        }
        return ApiResponse.ok(automation.create(
                new AutomationApplicationApi.CreateScheduleCommand(
                        tenant(authentication), user(authentication), agentId,
                        request.description(), request.prompt(), request.type(),
                        request.cronExpr(), request.scheduledAt(), request.timezone(),
                        request.maxRetries() == null ? 1 : request.maxRetries())));
    }

    @PatchMapping("/{scheduleId}")
    public ApiResponse<AutomationApplicationApi.ScheduledTaskView> update(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody UpdateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        if (request.description() == null && request.prompt() == null
                && request.cronExpr() == null && request.scheduledAt() == null
                && request.timezone() == null && request.maxRetries() == null) {
            throw new IllegalArgumentException("Automation patch must contain a mutable field");
        }
        return ApiResponse.ok(automation.update(
                new AutomationApplicationApi.UpdateScheduleCommand(
                        tenant(authentication), user(authentication), agentId, scheduleId.toString(),
                        request.description(), request.prompt(), request.cronExpr(),
                        request.scheduledAt(), request.timezone(), request.maxRetries(),
                        request.expectedRevision())));
    }

    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Void> archive(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        automation.archive(actor(authentication, agentId, scheduleId.toString()));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{scheduleId}/pause")
    public ApiResponse<AutomationApplicationApi.ScheduledTaskView> pause(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(automation.pause(actor(authentication, agentId, scheduleId.toString())));
    }

    @PostMapping("/{scheduleId}/resume")
    public ApiResponse<AutomationApplicationApi.ScheduledTaskView> resume(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(automation.resume(actor(authentication, agentId, scheduleId.toString())));
    }

    @PostMapping("/{scheduleId}/trigger")
    public ApiResponse<AutomationApplicationApi.TaskExecutionView> trigger(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TriggerRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(automation.trigger(new AutomationApplicationApi.TriggerCommand(
                tenant(authentication), user(authentication), agentId, scheduleId.toString(),
                idempotencyKey, request == null ? null : request.approvalId())));
    }

    @GetMapping("/{scheduleId}/executions")
    public ApiResponse<List<AutomationApplicationApi.TaskExecutionView>> executions(
            @PathVariable String agentId,
            @PathVariable UUID scheduleId,
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication) {
        return ApiResponse.ok(automation.executions(
                tenant(authentication), user(authentication), agentId, scheduleId.toString(), limit));
    }

    private AutomationApplicationApi.ActorScheduleCommand actor(
            Authentication authentication, String agentId, String scheduleId) {
        return new AutomationApplicationApi.ActorScheduleCommand(
                tenant(authentication), user(authentication), agentId, scheduleId);
    }

    private String tenant(Authentication authentication) {
        return PlatformHttpSupport.tenantId(authentication);
    }

    private String user(Authentication authentication) {
        return PlatformHttpSupport.userId(authentication);
    }

    public record CreateRequest(
            String agentId,
            @NotBlank @Size(max = 200) String description,
            @NotBlank @Size(max = 32_000) String prompt,
            @NotBlank String type,
            @Size(max = 120) String cronExpr,
            Instant scheduledAt,
            @Size(max = 80) String timezone,
            @Min(0) @Max(10) Integer maxRetries) {}

    public record UpdateRequest(
            @Size(min = 1, max = 200) String description,
            @Size(min = 1, max = 32_000) String prompt,
            @Size(max = 120) String cronExpr,
            Instant scheduledAt,
            @Size(max = 80) String timezone,
            @Min(0) @Max(10) Integer maxRetries,
            @Min(1) Long expectedRevision) {}

    public record TriggerRequest(String approvalId) {}
}
