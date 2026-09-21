package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspace-bridges")
public class PlatformLocalWorkspaceBridgeHttpController {
    private static final String TOKEN_HEADER = "X-SpaceAgent-Bridge-Token";
    private final LocalWorkspaceBridgeApplicationApi api;
    public PlatformLocalWorkspaceBridgeHttpController(LocalWorkspaceBridgeApplicationApi api) { this.api = api; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedLocalWorkspaceBridgeView> register(
            @Valid @RequestBody RegisterRequest request, Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.register(new RegisterLocalWorkspaceBridgeCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                request.displayName(), request.deviceId(), request.rootHandle())));
    }

    @PostMapping("/{bridgeId}/heartbeat")
    public ApiResponse<LocalWorkspaceBridgeView> heartbeat(
            @PathVariable String bridgeId,
            @RequestHeader(TOKEN_HEADER) @Size(max = 256) String rawToken,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                bridgeId, rawToken)));
    }

    @GetMapping
    public ApiResponse<List<LocalWorkspaceBridgeView>> list(Authentication authentication) {
        return ApiResponse.ok(api.list(new ListLocalWorkspaceBridgesQuery(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication))));
    }

    @DeleteMapping("/{bridgeId}")
    public ApiResponse<Void> revoke(
            @PathVariable String bridgeId, Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        api.revoke(new RevokeLocalWorkspaceBridgeCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                bridgeId));
        return ApiResponse.ok(null);
    }

    /** Deliberately contains no local filesystem path. */
    public record RegisterRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 160) String deviceId,
            @NotBlank @Size(min = 8, max = 128) String rootHandle) { }
}
