package com.spaceagent.admin.command.http;

import com.spaceagent.admin.command.application.AdminCommandDispatchService;
import com.spaceagent.admin.shared.AdminApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1/mcp-registry")
public class AdminMcpRegistryCommandController {
    private final AdminCommandDispatchService commands;

    public AdminMcpRegistryCommandController(AdminCommandDispatchService commands) {
        this.commands = commands;
    }

    @PostMapping("/sync-jobs")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> requestSync(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.requestMcpRegistrySync(
                actor(jwt), session(jwt), idempotencyKey, request.reason(), requestId(requestId)));
    }

    @PostMapping("/candidates/{candidateId}/approvals")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> approve(
            @PathVariable @Size(max = 36) String candidateId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.approveMcpRegistryCandidate(
                actor(jwt), session(jwt), idempotencyKey, candidateId, request.reason(),
                requestId(requestId)));
    }

    @PostMapping("/candidates/{candidateId}/rejections")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> reject(
            @PathVariable @Size(max = 36) String candidateId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.rejectMcpRegistryCandidate(
                actor(jwt), session(jwt), idempotencyKey, candidateId, request.reason(),
                requestId(requestId)));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("session_id"));
    }

    private static String requestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        return value.substring(0, Math.min(120, value.length()));
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
