package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.api.McpOAuthApplicationApi;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mcp-marketplace")
public class PlatformMcpMarketplaceHttpController {
    private final McpMarketplaceApplicationApi api;
    private final McpConnectionQualificationApplicationApi qualification;
    private final McpOAuthApplicationApi oauth;

    public PlatformMcpMarketplaceHttpController(
            McpMarketplaceApplicationApi api,
            McpConnectionQualificationApplicationApi qualification,
            McpOAuthApplicationApi oauth) {
        this.api = api;
        this.qualification = qualification;
        this.oauth = oauth;
    }

    @GetMapping("/catalog")
    public ApiResponse<List<McpMarketplaceApplicationApi.EntryView>> catalog() {
        return ApiResponse.ok(api.catalog());
    }

    @GetMapping("/catalog/{entryId}/versions")
    public ApiResponse<List<McpMarketplaceApplicationApi.ServerVersionView>> versions(
            @PathVariable UUID entryId) {
        return ApiResponse.ok(api.versions(entryId.toString()));
    }

    @GetMapping("/catalog/{entryId}/versions/{versionId}")
    public ApiResponse<McpMarketplaceApplicationApi.ServerVersionView> version(
            @PathVariable UUID entryId,
            @PathVariable UUID versionId) {
        return ApiResponse.ok(api.version(entryId.toString(), versionId.toString()));
    }

    @GetMapping("/installations")
    public ApiResponse<List<McpMarketplaceApplicationApi.InstallationView>> installations(
            Authentication authentication) {
        return ApiResponse.ok(api.installations(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication)));
    }

    @PostMapping("/installations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpMarketplaceApplicationApi.InstallationView> install(
            @Valid @RequestBody InstallRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.install(new McpMarketplaceApplicationApi.InstallCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), request.entryId(),
                request.serverVersionId(), request.scope(), request.displayName())));
    }

    @PostMapping("/installations/{id}/disable")
    public ApiResponse<McpMarketplaceApplicationApi.InstallationView> disable(
            @PathVariable String id,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.disable(mutation(id, authentication)));
    }

    @GetMapping("/connections")
    public ApiResponse<List<McpMarketplaceApplicationApi.ConnectionView>> connections(
            Authentication authentication) {
        return ApiResponse.ok(api.connections(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication)));
    }

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpMarketplaceApplicationApi.ConnectionView> connect(
            @Valid @RequestBody ConnectRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), request.installationId(),
                request.endpointUrl(), request.authType(), request.auth())));
    }

    @PostMapping("/connections/{id}/revoke")
    public ApiResponse<McpMarketplaceApplicationApi.ConnectionView> revoke(
            @PathVariable String id,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.revokeConnection(mutation(id, authentication)));
    }

    @PostMapping("/connections/{id}/oauth/begin")
    public ApiResponse<McpOAuthApplicationApi.AuthorizationView> beginOAuth(
            @PathVariable String id,
            @Valid @RequestBody OAuthBeginRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(oauth.begin(new McpOAuthApplicationApi.BeginCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), id, request.redirectUri())));
    }

    @PostMapping("/oauth/complete")
    public ApiResponse<McpOAuthApplicationApi.GrantView> completeOAuth(
            @Valid @RequestBody OAuthCompleteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(oauth.complete(new McpOAuthApplicationApi.CompleteCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), request.state(),
                request.code(), request.error())));
    }

    @PostMapping("/connections/{id}/qualification")
    public ApiResponse<McpConnectionQualificationApplicationApi.QualificationView> qualify(
            @PathVariable String id,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(qualification.qualify(
                new McpConnectionQualificationApplicationApi.QualifyCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), id)));
    }

    @GetMapping("/connections/{id}/qualification")
    public ApiResponse<McpConnectionQualificationApplicationApi.QualificationView> qualification(
            @PathVariable String id,
            Authentication authentication) {
        return ApiResponse.ok(qualification.qualification(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), id));
    }

    @GetMapping("/connections/{id}/health-observations")
    public ApiResponse<List<McpConnectionQualificationApplicationApi.ObservationView>> observations(
            @PathVariable String id,
            @RequestParam(defaultValue = "25") int limit,
            Authentication authentication) {
        return ApiResponse.ok(qualification.observations(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), id, limit));
    }

    private static McpMarketplaceApplicationApi.MutateCommand mutation(
            String id, Authentication authentication) {
        return new McpMarketplaceApplicationApi.MutateCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), id);
    }

    public record InstallRequest(
            @NotBlank
            @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
            String entryId,
            @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
            String serverVersionId,
            @NotNull McpInstallationScope scope,
            @Size(max = 120) String displayName) {
    }

    public record ConnectRequest(
            @NotBlank String installationId,
            @NotBlank @Size(max = 1_000) String endpointUrl,
            McpAuthType authType,
            Map<String, String> auth) {
    }

    public record OAuthBeginRequest(
            @NotBlank @Size(max = 2_000) String redirectUri) {
    }

    public record OAuthCompleteRequest(
            @NotBlank @Size(max = 512) String state,
            @Size(max = 2_048) String code,
            @Size(max = 100) String error) {
    }
}
