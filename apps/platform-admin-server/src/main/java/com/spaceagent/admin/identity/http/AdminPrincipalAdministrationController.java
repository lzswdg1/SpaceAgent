package com.spaceagent.admin.identity.http;

import com.spaceagent.admin.identity.application.AdminPrincipalAdministrationService;
import com.spaceagent.admin.shared.AdminApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1/administrators")
public class AdminPrincipalAdministrationController {
    private final AdminPrincipalAdministrationService service;

    public AdminPrincipalAdministrationController(AdminPrincipalAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    public AdminApiResponse<AdminPrincipalAdministrationService.AdministratorPage> administrators(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.administrators(page, pageSize, query, status,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @PostMapping
    public AdminApiResponse<AdminPrincipalAdministrationService.PrincipalCommandResult> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.create(actor(jwt), session(jwt), idempotencyKey,
                request.loginName(), request.displayName(), request.reason(), requestId(requestId)));
    }

    @PostMapping("/{principalId}/suspensions")
    public AdminApiResponse<AdminPrincipalAdministrationService.PrincipalCommandResult> suspend(
            @PathVariable UUID principalId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody LifecycleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.suspend(actor(jwt), session(jwt), principalId,
                idempotencyKey, request.reason(), requestId(requestId)));
    }

    @PostMapping("/{principalId}/restorations")
    public AdminApiResponse<AdminPrincipalAdministrationService.PrincipalCommandResult> restore(
            @PathVariable UUID principalId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody LifecycleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.restore(actor(jwt), session(jwt), principalId,
                idempotencyKey, request.reason(), requestId(requestId)));
    }

    @PostMapping("/{principalId}/credential-recoveries")
    public AdminApiResponse<AdminPrincipalAdministrationService.PrincipalCommandResult>
            recoverCredentials(
            @PathVariable UUID principalId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody LifecycleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.resetCredentials(actor(jwt), session(jwt), principalId,
                idempotencyKey, request.reason(), requestId(requestId)));
    }

    @GetMapping("/{principalId}/sessions")
    public AdminApiResponse<AdminPrincipalAdministrationService.SessionPage> sessions(
            @PathVariable UUID principalId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.sessions(principalId, page, pageSize, session(jwt),
                actor(jwt), requestId(requestId)));
    }

    @PostMapping("/{principalId}/sessions/{sessionId}/revocations")
    public AdminApiResponse<AdminPrincipalAdministrationService.PrincipalCommandResult> revokeSession(
            @PathVariable UUID principalId,
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody LifecycleRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(service.revokeSession(actor(jwt), session(jwt), principalId,
                sessionId, idempotencyKey, request.reason(), requestId(requestId)));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("session_id"));
    }
    private static String requestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        return value.substring(0, Math.min(120, value.length()));
    }

    public record CreateRequest(
            @NotBlank @Size(min = 3, max = 120) String loginName,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record LifecycleRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
