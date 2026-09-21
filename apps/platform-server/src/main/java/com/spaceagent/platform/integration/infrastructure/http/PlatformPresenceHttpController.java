package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.IdentityPresenceApi;
import com.spaceagent.platform.integration.infrastructure.PlatformTokenIssuer;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformPresenceHttpController {
    private final IdentityPresenceApi presence;
    private final PlatformTokenIssuer tokens;
    public PlatformPresenceHttpController(IdentityPresenceApi presence, PlatformTokenIssuer tokens) {
        this.presence = presence;
        this.tokens = tokens;
    }
    @PostMapping("/api/v1/presence/heartbeat")
    public ApiResponse<IdentityPresenceApi.Lease> heartbeat(Authentication authentication,
            @RequestBody(required = false) HeartbeatRequest request) {
        String token = (String) authentication.getCredentials();
        var context = tokens.accessContext(token);
        return ApiResponse.ok(presence.heartbeat(new IdentityPresenceApi.Heartbeat(context.sessionId(),
                context.userId(), context.tenantId(), context.accessVersion(), hash(token),
                request == null ? "UNKNOWN" : request.clientType(), context.expiresAt())));
    }
    @PostMapping("/api/v1/presence/leave")
    public ApiResponse<Void> leave(Authentication authentication) {
        var context = tokens.accessContext((String) authentication.getCredentials());
        presence.leave(context.sessionId(), context.userId());
        return ApiResponse.ok(null);
    }
    @GetMapping("/internal/system-admin/v1/presence")
    public ApiResponse<IdentityPresenceApi.Summary> summary() { return ApiResponse.ok(presence.summary()); }
    public record HeartbeatRequest(String clientType) { }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
