package com.spaceagent.admin.command.http;

import com.spaceagent.admin.command.application.AdminCommandDispatchService;
import com.spaceagent.admin.shared.AdminApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1/organizations")
public class AdminOrganizationCommandController {
    private final AdminCommandDispatchService commands;

    public AdminOrganizationCommandController(AdminCommandDispatchService commands) {
        this.commands = commands;
    }

    @PostMapping
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.createOrganization(actor(jwt), session(jwt),
                idempotencyKey, request.ownerUserId(), request.name(), request.slug(),
                request.reason(), requestId(requestId)));
    }

    @PatchMapping("/{organizationId}")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> update(
            @PathVariable @Size(max = 36) String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody UpdateRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.updateOrganization(actor(jwt), session(jwt),
                idempotencyKey, organizationId, request.name(), request.slug(), request.reason(),
                requestId(requestId)));
    }

    @PostMapping("/{organizationId}/deletion-jobs")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> delete(
            @PathVariable @Size(max = 36) String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.deleteOrganization(actor(jwt), session(jwt),
                idempotencyKey, organizationId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/{organizationId}/members")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> addMember(
            @PathVariable @Size(max = 36) String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody MemberRoleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.addOrganizationMember(actor(jwt), session(jwt),
                idempotencyKey, organizationId, request.userId(), request.role(), request.reason(),
                requestId(requestId)));
    }

    @PatchMapping("/{organizationId}/members/{userId}")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> updateMemberRole(
            @PathVariable @Size(max = 36) String organizationId,
            @PathVariable @Size(max = 36) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody RoleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.updateOrganizationMemberRole(actor(jwt), session(jwt),
                idempotencyKey, organizationId, userId, request.role(), request.reason(),
                requestId(requestId)));
    }

    @DeleteMapping("/{organizationId}/members/{userId}")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> removeMember(
            @PathVariable @Size(max = 36) String organizationId,
            @PathVariable @Size(max = 36) String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody ReasonRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.removeOrganizationMember(actor(jwt), session(jwt),
                idempotencyKey, organizationId, userId, request.reason(), requestId(requestId)));
    }

    @PostMapping("/{organizationId}/ownership-transfers")
    public AdminApiResponse<AdminCommandDispatchService.CommandResult> transferOwnership(
            @PathVariable @Size(max = 36) String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody TransferOwnershipRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(commands.transferOrganizationOwnership(actor(jwt), session(jwt),
                idempotencyKey, organizationId, request.newOwnerUserId(), request.reason(),
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

    public record CreateRequest(
            @NotBlank @Size(max = 36) String ownerUserId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) String slug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) String slug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record MemberRoleRequest(
            @NotBlank @Size(max = 36) String userId,
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record RoleRequest(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record TransferOwnershipRequest(
            @NotBlank @Size(max = 36) String newOwnerUserId,
            @NotBlank @Size(max = 500) String reason) {
    }
}
