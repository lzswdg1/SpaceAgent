package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.AuthenticateIdentityCommand;
import com.spaceagent.platform.identity.api.AuthenticatedIdentityView;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityAuthenticationApi;
import com.spaceagent.platform.identity.api.IdentitySessionView;
import com.spaceagent.platform.identity.api.RegisterIdentityCommand;
import com.spaceagent.platform.identity.api.UpdateUserProfileCommand;
import com.spaceagent.platform.identity.api.UserProfileView;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.integration.infrastructure.PlatformTokenIssuer;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.auth.AuthToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Instant;
import java.util.Map;

/**
 * Public identity/auth HTTP adapter.
 *
 * <p>Controllers contain no business logic; they map HTTP requests to the public
 * identity application APIs and turn authenticated identity into bearer tokens.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformIdentityHttpController {
    private static final String BROWSER_REFRESH_COOKIE = "spaceagent_refresh";

    private final IdentityAuthenticationApi authenticationApi;
    private final IdentityApplicationApi identityApi;
    private final PlatformTokenIssuer tokenIssuer;
    private final PlatformRequestAdmissionService admission;

    public PlatformIdentityHttpController(
            IdentityAuthenticationApi authenticationApi,
            IdentityApplicationApi identityApi,
            PlatformTokenIssuer tokenIssuer,
            PlatformRequestAdmissionService admission) {
        this.authenticationApi = authenticationApi;
        this.identityApi = identityApi;
        this.tokenIssuer = tokenIssuer;
        this.admission = admission;
    }

    @PostMapping("/auth/register")
    public ApiResponse<AuthToken> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("register", request.username(), httpRequest, 5);
        return ApiResponse.ok(tokenIssuer.issue(authenticationApi.register(new RegisterIdentityCommand(
                request.username(), request.password(), request.displayName()))));
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthToken> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("login", request.username(), httpRequest, 10);
        return ApiResponse.ok(tokenIssuer.issue(authenticationApi.authenticate(new AuthenticateIdentityCommand(
                request.username(), request.password()))));
    }

    @PostMapping("/auth/refresh")
    public ApiResponse<AuthToken> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("refresh", request.refreshToken(), httpRequest, 30);
        return ApiResponse.ok(tokenIssuer.refresh(request.refreshToken()));
    }

    @PostMapping("/web/auth/register")
    public ApiResponse<BrowserAuthToken> browserRegister(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        admission.requireAuthenticationAttempt("register", request.username(), httpRequest, 5);
        return browserIssue(authenticationApi.register(new RegisterIdentityCommand(
                request.username(), request.password(), request.displayName())),
                httpRequest, httpResponse);
    }

    @PostMapping("/web/auth/login")
    public ApiResponse<BrowserAuthToken> browserLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        admission.requireAuthenticationAttempt("login", request.username(), httpRequest, 10);
        return browserIssue(authenticationApi.authenticate(new AuthenticateIdentityCommand(
                request.username(), request.password())), httpRequest, httpResponse);
    }

    @PostMapping("/web/auth/refresh")
    public ApiResponse<BrowserAuthToken> browserRefresh(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String refreshToken = browserRefreshToken(httpRequest);
        admission.requireAuthenticationAttempt("refresh", refreshToken, httpRequest, 30);
        AuthToken token = tokenIssuer.refresh(refreshToken);
        setBrowserRefreshCookie(httpRequest, httpResponse, token.refreshToken());
        return ApiResponse.ok(BrowserAuthToken.from(token));
    }

    @PostMapping("/web/organizations/{organizationId}/switch")
    public ApiResponse<BrowserAuthToken> browserSwitchOrganization(
            @org.springframework.web.bind.annotation.PathVariable String organizationId,
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        PlatformHttpSupport.requireWrite(authentication);
        AuthToken token = tokenIssuer.switchOrganization(
                PlatformHttpSupport.userId(authentication), organizationId);
        setBrowserRefreshCookie(httpRequest, httpResponse, token.refreshToken());
        return ApiResponse.ok(BrowserAuthToken.from(token));
    }

    @PostMapping("/web/auth/logout")
    public ApiResponse<Void> browserLogout(
            Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        tokenIssuer.logout(
                PlatformHttpSupport.userId(authentication),
                bearerToken(httpRequest.getHeader(HttpHeaders.AUTHORIZATION)),
                optionalBrowserRefreshToken(httpRequest));
        clearBrowserRefreshCookie(httpRequest, httpResponse);
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @RequestBody(required = false) LogoutRequest request) {
        tokenIssuer.logout(
                PlatformHttpSupport.userId(authentication),
                bearerToken(httpRequest.getHeader("Authorization")),
                request == null ? null : request.refreshToken());
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/change-password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        authenticationApi.changePassword(
                PlatformHttpSupport.userId(authentication),
                request.oldPassword(),
                request.newPassword());
        return ApiResponse.ok(null);
    }

    @GetMapping("/users/me")
    public ApiResponse<UserResponse> currentUser(Authentication authentication) {
        UserView user = identityApi.findUser(PlatformHttpSupport.userId(authentication))
                .orElseThrow();
        return ApiResponse.ok(UserResponse.from(user, authentication));
    }

    @PutMapping("/users/me")
    public ApiResponse<UserResponse> updateCurrentUser(
            @Valid @RequestBody UpdateCurrentUserRequest request,
            Authentication authentication) {
        return ApiResponse.ok(UserResponse.from(identityApi.updateDisplayName(
                PlatformHttpSupport.userId(authentication),
                request.displayName()), authentication));
    }

    @GetMapping("/users/me/profile")
    public ApiResponse<UserProfileResponse> currentProfile(Authentication authentication) {
        String userId = PlatformHttpSupport.userId(authentication);
        UserProfileView profile = identityApi.findProfile(userId)
                .orElseGet(() -> identityApi.saveProfile(new UpdateUserProfileCommand(
                        userId, null, null, null)));
        return ApiResponse.ok(UserProfileResponse.from(profile));
    }

    @PutMapping("/users/me/profile")
    public ApiResponse<UserProfileResponse> updateCurrentProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        String userId = PlatformHttpSupport.userId(authentication);
        return ApiResponse.ok(UserProfileResponse.from(identityApi.saveProfile(new UpdateUserProfileCommand(
                userId,
                request.preferredTone(),
                request.timezone(),
                request.summary()))));
    }

    @GetMapping("/public/auth-config")
    public ApiResponse<Map<String, Object>> authConfig() {
        return ApiResponse.ok(Map.of(
                "socialProviders", Map.of(),
                "registrationEnabled", true));
    }

    @GetMapping("/public/branding")
    public ApiResponse<Map<String, String>> branding() {
        return ApiResponse.ok(Map.of("appName", "SpaceAgent"));
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        return authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
    }

    private ApiResponse<BrowserAuthToken> browserIssue(
            AuthenticatedIdentityView identity,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthToken token = tokenIssuer.issue(identity);
        setBrowserRefreshCookie(request, response, token.refreshToken());
        return ApiResponse.ok(BrowserAuthToken.from(token));
    }

    private ApiResponse<BrowserAuthToken> browserIssue(
            IdentitySessionView session,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthToken token = tokenIssuer.issue(session);
        setBrowserRefreshCookie(request, response, token.refreshToken());
        return ApiResponse.ok(BrowserAuthToken.from(token));
    }

    private static String browserRefreshToken(HttpServletRequest request) {
        String value = optionalBrowserRefreshToken(request);
        if (value == null) {
            throw new com.spaceagent.shared.exception.BusinessException(
                    "Browser session is unavailable", org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "BROWSER_REFRESH_COOKIE_REQUIRED");
        }
        return value;
    }

    private static String optionalBrowserRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (BROWSER_REFRESH_COOKIE.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void setBrowserRefreshCookie(
            HttpServletRequest request,
            HttpServletResponse response,
            String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(
                        BROWSER_REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api/v1/web")
                .build().toString());
    }

    private static void clearBrowserRefreshCookie(
            HttpServletRequest request,
            HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BROWSER_REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api/v1/web")
                .maxAge(0)
                .build().toString());
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 10, max = 64) String password,
            @Size(max = 64) String displayName) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 64) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
    }

    public record BrowserAuthToken(
            String token,
            String userId,
            String username,
            String role,
            String tenantId,
            String tenantRole,
            Instant expiresAt,
            Instant refreshExpiresAt) {
        static BrowserAuthToken from(AuthToken token) {
            return new BrowserAuthToken(
                    token.token(), token.userId(), token.username(), token.role(),
                    token.tenantId(), token.tenantRole(), token.expiresAt(), token.refreshExpiresAt());
        }
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 10, max = 64) String newPassword) {
    }

    public record UpdateCurrentUserRequest(
            @NotBlank @Size(max = 64) String displayName) {
    }

    public record UpdateProfileRequest(
            @Size(max = 32) String preferredTone,
            @Size(max = 64) String timezone,
            @Size(max = 1000) String summary) {
    }

    public record UserResponse(
            String userId,
            String username,
            String displayName,
            String role,
            String tenantId,
            String tenantRole,
            Instant createdAt) {

        static UserResponse from(UserView user, Authentication authentication) {
            return new UserResponse(
                    user.id(),
                    user.externalId(),
                    user.displayName(),
                    "USER",
                    PlatformHttpSupport.tenantId(authentication),
                    PlatformHttpSupport.tenantRole(authentication),
                    user.createdAt());
        }
    }

    public record UserProfileResponse(
            String userId,
            String preferredTone,
            String timezone,
            String summary,
            Instant createdAt,
            Instant updatedAt) {

        static UserProfileResponse from(UserProfileView profile) {
            return new UserProfileResponse(
                    profile.userId(),
                    profile.preferredTone(),
                    profile.timezone(),
                    profile.summary(),
                    profile.createdAt(),
                    profile.updatedAt());
        }
    }
}
