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
@RequestMapping("/admin/v1/artifact-objects")
public class AdminArtifactCommandController {
    private final AdminCommandDispatchService commands;

    public AdminArtifactCommandController(AdminCommandDispatchService commands) {
        this.commands = commands;
    }

    @PostMapping("/{objectId}/deletion-retries")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> retryDeletion(
            @PathVariable @Size(max = 160) String objectId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody RetryRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.retryArtifactDeletion(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("session_id")),
                idempotencyKey, objectId, request.tenantId(), request.reason(),
                requestId(requestId)));
    }

    private static String requestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        return value.substring(0, Math.min(120, value.length()));
    }

    public record RetryRequest(
            @NotBlank @Size(max = 64) String tenantId,
            @NotBlank @Size(max = 500) String reason) { }
}
