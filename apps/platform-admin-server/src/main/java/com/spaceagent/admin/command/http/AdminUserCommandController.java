package com.spaceagent.admin.command.http;

import com.spaceagent.admin.command.application.AdminCommandDispatchService;
import com.spaceagent.admin.shared.AdminApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1")
public class AdminUserCommandController {
    private final AdminCommandDispatchService commands;

    public AdminUserCommandController(AdminCommandDispatchService commands) {
        this.commands = commands;
    }

    @PostMapping("/users")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> createUser(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.createUser(actor(jwt), session(jwt), idempotencyKey,
                request.loginName(), request.displayName(), request.organizationName(),
                request.organizationSlug(), request.reason(), requestId(requestId)));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/users/{userId}")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> updateUser(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody UserProfileRequest request, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.updateUser(actor(jwt), session(jwt), idempotencyKey, userId,
                request.displayName(), request.reason(), requestId(requestId)));
    }

    @PostMapping("/users/{userId}/session-revocations")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> revokeSessions(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.revokeUserSessions(actor(jwt), session(jwt), idempotencyKey,
                userId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/users/{userId}/password-resets")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> passwordReset(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.resetUserPassword(actor(jwt), session(jwt), idempotencyKey,
                userId, request.reason(), requestId(requestId)));
    }

    public record UserProfileRequest(@NotBlank @Size(max = 120) String displayName,
                                     @NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/users/{userId}/suspend")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> suspendUser(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.suspendUser(actor(jwt), session(jwt), idempotencyKey,
                userId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/users/{userId}/restore")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> restoreUser(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.restoreUser(actor(jwt), session(jwt), idempotencyKey,
                userId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/users/{userId}/deletion-requests")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> deletionPreflight(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.deletionPreflight(actor(jwt), session(jwt),
                idempotencyKey, userId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/users/{userId}/deletion-jobs")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> deleteUser(
            @PathVariable @Size(max = 160) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.deleteUser(actor(jwt), session(jwt), idempotencyKey,
                userId, request.reason(), requestId(requestId)));
    }

    @GetMapping("/users/{userId}/deletion-jobs/current")
    public AdminApiResponse<com.spaceagent.admin.platformclient.PlatformAdminWire.UserCleanupJobProjection>
            userCleanupJob(
            @PathVariable @Size(max = 160) String userId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.userCleanupJob(actor(jwt), session(jwt), userId,
                requestId(requestId)));
    }

    @PostMapping("/commands/{commandId}/reconcile")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> reconcile(
            @PathVariable UUID commandId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.reconcile(actor(jwt), session(jwt), commandId,
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

    public record CreateUserRequest(
            @NotBlank @Size(max = 255) String loginName,
            @Size(max = 120) String displayName,
            @Size(max = 120) String organizationName,
            @Size(max = 63) String organizationSlug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
