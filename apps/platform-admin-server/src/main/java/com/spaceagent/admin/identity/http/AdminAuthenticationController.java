package com.spaceagent.admin.identity.http;

import com.spaceagent.admin.config.AdminSecurityProperties;
import com.spaceagent.admin.identity.application.AdminAuthenticatedSession;
import com.spaceagent.admin.identity.application.AdminAuthenticationService;
import com.spaceagent.admin.identity.application.AdminLoginAdmissionService;
import com.spaceagent.admin.identity.application.AdminIdentityView;
import com.spaceagent.admin.shared.AdminApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/admin/v1/auth")
public class AdminAuthenticationController {
    public static final String REFRESH_COOKIE = "spaceagent_admin_refresh";
    public static final String CSRF_COOKIE = "spaceagent_admin_csrf";
    public static final String CSRF_HEADER = "X-Admin-CSRF";

    private final AdminAuthenticationService authenticationService;
    private final AdminSecurityProperties securityProperties;
    private final AdminLoginAdmissionService loginAdmission;

    public AdminAuthenticationController(
            AdminAuthenticationService authenticationService,
            AdminSecurityProperties securityProperties,
            AdminLoginAdmissionService loginAdmission) {
        this.authenticationService = authenticationService;
        this.securityProperties = securityProperties;
        this.loginAdmission = loginAdmission;
    }

    @PostMapping("/login")
    public ResponseEntity<AdminApiResponse<SessionResponse>> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            HttpServletRequest httpRequest) {
        loginAdmission.requireAttempt(
                httpRequest.getRemoteAddr(), request.loginName(), securityProperties.getMaxLoginAttempts());
        return sessionResponse(authenticationService.login(
                request.loginName(), request.password(), requestId(requestId)));
    }

    @PostMapping({"/mfa/verify", "/mfa/recovery", "/mfa/reauthenticate", "/password", "/recovery-codes/rotation"})
    public void retiredAuthenticationFlow() {
        throw new com.spaceagent.admin.shared.AdminApiException(org.springframework.http.HttpStatus.GONE,
                "ADMIN_AUTH_FLOW_RETIRED", "Administrator credentials are managed by environment configuration");
    }

    @PostMapping("/refresh")
    public ResponseEntity<AdminApiResponse<SessionResponse>> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(value = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfHeader,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return sessionResponse(authenticationService.refresh(
                refreshToken, csrfHeader, csrfCookie, requestId(requestId)));
    }

    @PostMapping("/logout")
    public ResponseEntity<AdminApiResponse<Void>> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(value = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfHeader,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        authenticationService.logout(refreshToken, csrfHeader, csrfCookie, requestId(requestId));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString())
                .body(AdminApiResponse.ok(null));
    }

    private ResponseEntity<AdminApiResponse<SessionResponse>> sessionResponse(
            AdminAuthenticatedSession session) {
        Duration maxAge = Duration.between(Instant.now(), session.refreshTokenExpiresAt());
        SessionResponse body = new SessionResponse(session.accessToken(),
                session.accessTokenExpiresAt(), session.csrfToken(), session.administrator());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(session.refreshToken(), maxAge.isNegative() ? Duration.ZERO : maxAge).toString())
                .header(HttpHeaders.SET_COOKIE,
                        csrfCookie(session.csrfToken(), maxAge.isNegative() ? Duration.ZERO : maxAge).toString())
                .body(AdminApiResponse.ok(body));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(securityProperties.isCookieSecure())
                .sameSite("Strict")
                .path("/admin/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .httpOnly(false)
                .secure(securityProperties.isCookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static String requestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.substring(0, Math.min(120, value.length()));
    }

    public record LoginRequest(
            @NotBlank @Size(max = 120) String loginName,
            @NotBlank @Size(max = 512) String password) {
    }

    public record SessionResponse(
            String accessToken,
            Instant accessTokenExpiresAt,
            String csrfToken,
            AdminIdentityView administrator) {
    }
}
