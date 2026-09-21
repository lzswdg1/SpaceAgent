package com.spaceagent.admin.identity.application;

import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.config.AdminSecurityProperties;
import com.spaceagent.admin.identity.domain.AdminAuthenticationRecord;
import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.identity.domain.AdminSession;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import com.spaceagent.admin.security.AdminAccessToken;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.security.AdminTokenService;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminAuthenticationService {
    private static final String INVALID_CREDENTIALS = "Administrator credentials are invalid";

    private final AdminIdentityRepository repository;
    private final AdminSecurityProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService tokenService;
    private final AdminAuditService auditService;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthenticationService(
            AdminIdentityRepository repository,
            AdminSecurityProperties properties,
            PasswordEncoder adminPasswordEncoder,
            AdminTokenService tokenService,
            AdminAuditService auditService,
            Clock adminClock) {
        this.repository = repository;
        this.properties = properties;
        this.passwordEncoder = adminPasswordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.clock = adminClock;
        this.dummyPasswordHash = passwordEncoder.encode(AdminTokenMaterial.randomToken());
    }

    @Transactional(noRollbackFor = AdminApiException.class)
    public AdminAuthenticatedSession login(String loginName, String password, String requestId) {
        Instant now = clock.instant();
        String normalized = normalizeLogin(loginName);
        String subjectHash = AdminTokenMaterial.sha256(normalized);
        AdminAuthenticationRecord authentication = repository.findAuthenticationByLoginForUpdate(normalized)
                .orElse(null);
        String storedHash = authentication == null ? dummyPasswordHash : authentication.passwordHash();
        boolean passwordMatches = password != null && passwordEncoder.matches(password, storedHash);
        if (authentication == null || !authentication.principal().canAuthenticate() || !passwordMatches) {
            repository.recordLoginAttempt(subjectHash, false, "ADMIN_INVALID_CREDENTIALS", now);
            auditService.append(authentication == null ? null : authentication.principal().id(), null,
                    "ADMIN_LOGIN_PASSWORD", "ADMIN_PRINCIPAL", null, requestId, subjectHash,
                    AdminAuditOutcome.DENIED, "ADMIN_INVALID_CREDENTIALS");
            throw new AdminApiException(HttpStatus.UNAUTHORIZED,
                    "ADMIN_INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }

        repository.recordLoginAttempt(subjectHash, true, null, now);
        repository.updateLastSuccessfulLogin(authentication.principal().id(), now);
        SystemAdministrator loggedIn = repository.findPrincipalById(authentication.principal().id()).orElseThrow(this::invalidSession);
        AdminAuthenticatedSession result = createSession(loggedIn, now);
        auditService.append(loggedIn.id(), null, "ADMIN_LOGIN_PASSWORD", "ADMIN_PRINCIPAL",
                loggedIn.id().toString(), requestId, subjectHash, AdminAuditOutcome.SUCCEEDED, null);
        return result;
    }

    @Transactional
    public AdminAuthenticatedSession refresh(
            String refreshToken, String csrfHeader, String csrfCookie, String requestId) {
        Instant now = clock.instant();
        String refreshHash = AdminTokenMaterial.sha256(requireToken(refreshToken));
        AdminSession existing = repository.findSessionByRefreshHashForUpdate(refreshHash)
                .orElseThrow(this::invalidSession);
        if (!existing.activeAt(now) || !validCsrf(existing, csrfHeader, csrfCookie)) {
            throw invalidSession();
        }
        AdminAuthenticationRecord authentication = repository
                .findAuthenticationByPrincipalId(existing.principalId())
                .filter(value -> value.principal().canAuthenticate()
                        && value.principal().credentialVersion() == existing.credentialVersion())
                .orElseThrow(this::invalidSession);

        String replacementRefresh = AdminTokenMaterial.randomToken();
        String replacementHash = AdminTokenMaterial.sha256(replacementRefresh);
        if (!repository.revokeSession(existing.id(), replacementHash, now)) {
            throw invalidSession();
        }
        AdminAuthenticatedSession result = createSession(
                authentication.principal(), now, replacementRefresh, existing.authenticatedAt());
        auditService.append(authentication.principal().id(), existing.id(), "ADMIN_SESSION_REFRESH",
                "ADMIN_SESSION", existing.id().toString(), requestId, refreshHash,
                AdminAuditOutcome.SUCCEEDED, null);
        return result;
    }

    @Transactional
    public void logout(String refreshToken, String csrfHeader, String csrfCookie, String requestId) {
        Instant now = clock.instant();
        String refreshHash = AdminTokenMaterial.sha256(requireToken(refreshToken));
        AdminSession existing = repository.findSessionByRefreshHashForUpdate(refreshHash)
                .orElseThrow(this::invalidSession);
        if (!existing.activeAt(now) || !validCsrf(existing, csrfHeader, csrfCookie)) {
            throw invalidSession();
        }
        repository.revokeSession(existing.id(), null, now);
        auditService.append(existing.principalId(), existing.id(), "ADMIN_LOGOUT", "ADMIN_SESSION",
                existing.id().toString(), requestId, refreshHash, AdminAuditOutcome.SUCCEEDED, null);
    }

    public AdminIdentityView me(UUID administratorId) {
        return repository.findPrincipalById(administratorId)
                .filter(SystemAdministrator::canAuthenticate)
                .map(AdminIdentityView::from)
                .orElseThrow(this::invalidSession);
    }

    @Transactional(readOnly = true)
    public void requireActiveSession(UUID principalId, UUID sessionId) {
        Instant now = clock.instant();
        var principal = repository.findPrincipalById(principalId)
                .filter(SystemAdministrator::canAuthenticate).orElseThrow(this::invalidSession);
        repository.findSessionById(sessionId)
                .filter(value -> value.principalId().equals(principalId) && value.activeAt(now)
                        && value.credentialVersion() == principal.credentialVersion())
                .orElseThrow(this::invalidSession);
    }

    private AdminAuthenticatedSession createSession(SystemAdministrator administrator, Instant now) {
        return createSession(administrator, now, AdminTokenMaterial.randomToken(), now);
    }

    private AdminAuthenticatedSession createSession(
            SystemAdministrator administrator,
            Instant now,
            String rawRefreshToken) {
        return createSession(administrator, now, rawRefreshToken, now);
    }

    private AdminAuthenticatedSession createSession(
            SystemAdministrator administrator,
            Instant now,
            String rawRefreshToken,
            Instant authenticatedAt) {
        String csrfToken = AdminTokenMaterial.randomToken();
        Instant refreshExpiresAt = now.plus(properties.getRefreshTokenDays(), ChronoUnit.DAYS);
        UUID sessionId = UUID.randomUUID();
        repository.saveSession(new AdminSession(sessionId, administrator.id(),
                AdminTokenMaterial.sha256(rawRefreshToken), AdminTokenMaterial.sha256(csrfToken),
                administrator.credentialVersion(), authenticatedAt, refreshExpiresAt,
                null, null, now, now));
        AdminAccessToken accessToken = tokenService.issue(administrator, sessionId);
        return new AdminAuthenticatedSession(accessToken.value(), accessToken.expiresAt(),
                rawRefreshToken, csrfToken, refreshExpiresAt, AdminIdentityView.from(administrator));
    }

    private AdminApiException invalidSession() {
        return new AdminApiException(HttpStatus.UNAUTHORIZED,
                "ADMIN_INVALID_SESSION", "Administrator session is invalid");
    }

    private boolean validCsrf(AdminSession session, String header, String cookie) {
        return AdminTokenMaterial.hashMatches(requireToken(header), session.csrfTokenHash())
                && AdminTokenMaterial.hashMatches(requireToken(cookie), session.csrfTokenHash());
    }

    private static String normalizeLogin(String loginName) {
        if (loginName == null || loginName.isBlank()) return "";
        return loginName.trim().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 14 || password.length() > 512
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*\\d.*") || password.matches("[A-Za-z0-9]*")) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_PASSWORD_POLICY_INVALID",
                    "Administrator password must be 14+ characters with upper, lower, digit and symbol");
        }
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw new AdminApiException(HttpStatus.UNAUTHORIZED,
                    "ADMIN_INVALID_SESSION", "Administrator session is invalid");
        }
        return value;
    }

}
