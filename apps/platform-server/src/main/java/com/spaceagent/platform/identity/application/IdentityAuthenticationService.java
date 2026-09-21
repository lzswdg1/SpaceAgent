package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.AuthenticateIdentityCommand;
import com.spaceagent.platform.identity.api.AuthenticatedIdentityView;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityAuthenticationApi;
import com.spaceagent.platform.identity.api.IdentitySessionApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySessionView;
import com.spaceagent.platform.identity.api.OrganizationApplicationApi;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.api.ProvisionedOrganizationView;
import com.spaceagent.platform.identity.api.RegisterIdentityCommand;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.identity.domain.IdentityCredentialRepository;
import com.spaceagent.platform.identity.domain.AccessTokenRevocation;
import com.spaceagent.platform.identity.domain.ConsumedRefreshToken;
import com.spaceagent.platform.identity.domain.IdentitySessionRepository;
import com.spaceagent.platform.identity.domain.RefreshTokenSession;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserCredential;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Identity authentication coordinator.
 *
 * <p>User and tenant state remain owned by {@link IdentityApplicationApi}; this
 * service adds only username/password credential state and authentication rules.
 */
@Service
@Transactional
public class IdentityAuthenticationService implements IdentityAuthenticationApi, IdentitySessionApplicationApi {

    private final IdentityApplicationApi identityApi;
    private final OrganizationApplicationApi organizationApi;
    private final IdentityCredentialRepository credentialRepository;
    private final IdentitySessionRepository sessionRepository;
    private final IdentityActivityApplicationApi activityApi;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final int refreshTokenExpirationDays;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyPasswordHash;

    public IdentityAuthenticationService(
            IdentityApplicationApi identityApi,
            OrganizationApplicationApi organizationApi,
            IdentityCredentialRepository credentialRepository,
            IdentitySessionRepository sessionRepository,
            IdentityActivityApplicationApi activityApi,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            @Value("${platform.security.refresh-token-expiration-days:30}") int refreshTokenExpirationDays) {
        this.identityApi = identityApi;
        this.organizationApi = organizationApi;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.activityApi = activityApi;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
        this.dummyPasswordHash = passwordEncoder.encode("spaceagent-invalid-credential-sentinel");
    }

    @Override
    public AuthenticatedIdentityView register(RegisterIdentityCommand command) {
        String username = normalizeUsername(command.username());
        if (credentialRepository.findByUsername(username).isPresent()
                || identityApi.isExternalIdentityReserved(username)) {
            throw new BusinessException("Username already taken: " + username, HttpStatus.CONFLICT);
        }

        Instant now = timeProvider.now();
        String tenantSlug = "personal-" + idGenerator.nextId().replace("-", "").substring(0, 16);
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? username
                : command.displayName().trim();
        ProvisionedOrganizationView provisioned = organizationApi.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        username,
                        displayName,
                        "Personal - " + displayName,
                        tenantSlug));
        UserView user = provisioned.user();
        TenantMembershipView membership = new TenantMembershipView(
                provisioned.membership().organizationId(),
                provisioned.membership().userId(),
                provisioned.membership().role(),
                provisioned.membership().status(),
                provisioned.membership().joinedAt(),
                provisioned.membership().updatedAt());
        credentialRepository.save(new UserCredential(
                user.id(),
                username,
                passwordEncoder.encode(command.password()),
                now,
                now));
        return toIdentity(user, username, membership);
    }

    @Override
    public IdentitySessionView authenticate(AuthenticateIdentityCommand command) {
        String username = normalizeUsername(command.username());
        UserCredential credential = credentialRepository.findByUsername(username).orElse(null);
        if (credential == null) {
            passwordEncoder.matches(command.password(), dummyPasswordHash);
            activityApi.recordLoginFailed(null, username, "INVALID_CREDENTIALS", "UNKNOWN");
            throw new BusinessException(
                    "Invalid username or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        if (!passwordEncoder.matches(command.password(), credential.passwordHash())) {
            activityApi.recordLoginFailed(
                    credential.userId(), username, "INVALID_CREDENTIALS", "UNKNOWN");
            throw new BusinessException(
                    "Invalid username or password",
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS");
        }
        long version = sessionRepository.lockAccessVersion(credential.userId());
        UserCredential currentCredential = credentialRepository.findByUserId(credential.userId())
                .filter(value -> username.equals(value.username()))
                .filter(value -> credential.passwordHash().equals(value.passwordHash()))
                .orElse(null);
        if (currentCredential == null) {
            activityApi.recordLoginFailed(
                    credential.userId(), username, "INVALID_CREDENTIALS", "UNKNOWN");
            throw new BusinessException(
                    "Invalid username or password",
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS");
        }
        if (!activityApi.isUserActive(credential.userId())) {
            activityApi.recordLoginFailed(
                    credential.userId(), username, "USER_NOT_ACTIVE", "UNKNOWN");
            throw new BusinessException(
                    "Invalid username or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
        UserView user = identityApi.findUser(credential.userId())
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.UNAUTHORIZED));
        TenantMembershipView membership = selectLoginMembership(user);
        IdentitySessionView session = createSession(
                user.id(), membership.tenantId(), currentCredential.username(),
                user.displayName(), membership.role(), version);
        activityApi.recordLoginSucceeded(user.id(), currentCredential.username(), "UNKNOWN");
        return session;
    }

    @Override
    public void changePassword(String userId, String oldPassword, String newPassword) {
        sessionRepository.lockAccessVersion(userId);
        UserCredential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND));
        if (!passwordEncoder.matches(oldPassword, credential.passwordHash())) {
            throw new BusinessException("Old password is incorrect", HttpStatus.BAD_REQUEST);
        }
        if (newPassword == null || newPassword.length() < 10 || newPassword.length() > 64) {
            throw new BusinessException(
                    "New password must be between 10 and 64 characters",
                    HttpStatus.BAD_REQUEST);
        }
        credentialRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
        sessionRepository.incrementAccessVersion(userId);
        sessionRepository.revokeAllRefreshTokens(userId, timeProvider.now());
    }

    @Override
    public IdentitySessionView openSession(AuthenticatedIdentityView identity) {
        long version = sessionRepository.lockAccessVersion(identity.userId());
        if (!activityApi.isUserActive(identity.userId())) {
            throw unauthorized("User is not active", "USER_NOT_ACTIVE");
        }
        UserView user = identityApi.findUser(identity.userId())
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        UserCredential credential = credentialRepository.findByUserId(identity.userId())
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        TenantMembershipView membership = requireActiveMembership(identity.tenantId(), identity.userId());
        IdentitySessionView session = createSession(
                user.id(),
                identity.tenantId(),
                credential.username(),
                user.displayName(),
                membership.role(),
                version);
        activityApi.recordLoginSucceeded(user.id(), credential.username(), "UNKNOWN");
        return session;
    }

    @Override
    public IdentitySessionView openSessionForOrganization(
            String userId,
            String organizationId) {
        long version = sessionRepository.lockAccessVersion(userId);
        if (!activityApi.isUserActive(userId)) {
            throw unauthorized("User is not active", "USER_NOT_ACTIVE");
        }
        UserView user = identityApi.findUser(userId)
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        var organization = identityApi.findTenant(organizationId)
                .filter(tenant -> tenant.status()
                        == com.spaceagent.platform.identity.domain.TenantStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Organization is not active",
                        HttpStatus.CONFLICT,
                        "ORGANIZATION_NOT_ACTIVE"));
        TenantMembershipView membership = requireActiveMembership(organization.id(), user.id());
        UserCredential credential = credentialRepository.findByUserId(user.id())
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        return createSession(
                user.id(), organization.id(), credential.username(),
                user.displayName(), membership.role(), version);
    }

    @Override
    public IdentitySessionView rotateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorized("Refresh token is required", "REFRESH_TOKEN_REQUIRED");
        }
        Instant now = timeProvider.now();
        String replacement = generateRefreshToken();
        String replacementHash = hash(refreshTokenSafe(replacement));
        String owner = sessionRepository.refreshTokenOwner(hash(refreshTokenSafe(refreshToken)), now)
                .orElseThrow(() -> unauthorized("Invalid or expired refresh token", "REFRESH_TOKEN_INVALID"));
        long version = sessionRepository.lockAccessVersion(owner);
        ConsumedRefreshToken consumed = sessionRepository.consumeRefreshToken(
                        hash(refreshTokenSafe(refreshToken)),
                        replacementHash,
                        now)
                .orElseThrow(() -> unauthorized(
                        "Invalid or expired refresh token",
                        "REFRESH_TOKEN_INVALID"));

        if (version != consumed.accessVersion()) {
            throw unauthorized("Session has been revoked", "REFRESH_TOKEN_INVALID");
        }

        TenantMembershipView membership = requireActiveMembership(consumed.tenantId(), consumed.userId());
        UserView user = identityApi.findUser(consumed.userId())
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        UserCredential credential = credentialRepository.findByUserId(consumed.userId())
                .orElseThrow(() -> unauthorized("User not found", "IDENTITY_NOT_FOUND"));
        Instant expiresAt = now.plus(refreshTokenExpirationDays, ChronoUnit.DAYS);
        sessionRepository.saveRefreshToken(new RefreshTokenSession(
                replacementHash,
                user.id(),
                consumed.tenantId(),
                membership.role(),
                expiresAt,
                now, consumed.sessionId(), version));
        return new IdentitySessionView(
                user.id(),
                consumed.tenantId(),
                credential.username(),
                user.displayName(),
                membership.role().name(),
                replacement,
                expiresAt, consumed.sessionId(), version);
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionRepository.revokeRefreshToken(hash(refreshTokenSafe(refreshToken)), timeProvider.now());
        }
    }

    @Override
    public void revokeAllRefreshTokens(String userId) {
        sessionRepository.incrementAccessVersion(userId);
        sessionRepository.revokeAllRefreshTokens(userId, timeProvider.now());
    }

    @Override
    public void revokeAccessToken(String accessToken, String userId, Instant expiresAt) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        sessionRepository.saveAccessTokenRevocation(new AccessTokenRevocation(
                hash(accessToken),
                userId,
                expiresAt,
                timeProvider.now()));
    }

    @Override
    public void revokeSession(SessionRevocationCommand command) {
        sessionRepository.lockAccessVersion(command.userId());
        Instant now = timeProvider.now();
        if (command.sessionId() != null) {
            sessionRepository.revokeRefreshSession(command.userId(), command.sessionId(), now);
        } else if (command.legacyRefreshToken() != null
                && !command.legacyRefreshToken().isBlank()) {
            sessionRepository.revokeRefreshToken(
                    hash(refreshTokenSafe(command.legacyRefreshToken())), now);
        }
        if (command.accessToken() != null && !command.accessToken().isBlank()) {
            sessionRepository.saveAccessTokenRevocation(new AccessTokenRevocation(
                    hash(command.accessToken()), command.userId(),
                    command.accessExpiresAt() == null ? now : command.accessExpiresAt(), now));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccessTokenRevoked(String accessToken) {
        return accessToken != null
                && !accessToken.isBlank()
                && sessionRepository.isAccessTokenRevoked(hash(accessToken), timeProvider.now());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccessVersionCurrent(String userId, long version) {
        return version >= 0 && sessionRepository.accessVersion(userId) == version;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionActive(String userId, java.util.UUID sessionId, long accessVersion) {
        return sessionId != null && accessVersion >= 0
                && sessionRepository.isRefreshSessionActive(
                        userId, sessionId, accessVersion, timeProvider.now());
    }

    private IdentitySessionView createSession(
            String userId,
            String tenantId,
            String username,
            String displayName,
            TenantRole tenantRole,
            long version) {
        Instant now = timeProvider.now();
        Instant expiresAt = now.plus(refreshTokenExpirationDays, ChronoUnit.DAYS);
        String refreshToken = generateRefreshToken();
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        sessionRepository.saveRefreshToken(new RefreshTokenSession(
                hash(refreshToken),
                userId,
                tenantId,
                tenantRole,
                expiresAt,
                now, sessionId, version));
        return new IdentitySessionView(
                userId,
                tenantId,
                username,
                displayName,
                tenantRole.name(),
                refreshToken,
                expiresAt, sessionId, version);
    }

    private TenantMembershipView requireActiveMembership(String tenantId, String userId) {
        return identityApi.findTenantMembership(tenantId, userId)
                .filter(membership -> membership.status()
                        == com.spaceagent.platform.identity.domain.TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "User is not an active member of tenant",
                        HttpStatus.FORBIDDEN,
                        "TENANT_MEMBERSHIP_REQUIRED"));
    }

    private TenantMembershipView selectLoginMembership(UserView user) {
        var primaryTenant = identityApi.findTenant(user.tenantId())
                .filter(tenant -> tenant.status() == TenantStatus.ACTIVE);
        var primaryMembership = identityApi.findTenantMembership(user.tenantId(), user.id())
                .filter(membership -> membership.status()
                        == com.spaceagent.platform.identity.domain.TenantMembershipStatus.ACTIVE);
        if (primaryTenant.isPresent() && primaryMembership.isPresent()) {
            return primaryMembership.orElseThrow();
        }
        return organizationApi.listOrganizations(user.id()).stream()
                .filter(summary -> summary.organization().status() == TenantStatus.ACTIVE)
                .findFirst()
                .map(summary -> new TenantMembershipView(
                        summary.membership().organizationId(),
                        summary.membership().userId(),
                        summary.membership().role(),
                        summary.membership().status(),
                        summary.membership().joinedAt(),
                        summary.membership().updatedAt()))
                .orElseThrow(() -> new BusinessException(
                        "User has no active Organization",
                        HttpStatus.FORBIDDEN,
                        "ORGANIZATION_MEMBERSHIP_REQUIRED"));
    }

    private AuthenticatedIdentityView toIdentity(
            UserView user,
            String username,
            TenantMembershipView membership) {
        return new AuthenticatedIdentityView(
                user.id(),
                membership.tenantId(),
                username,
                user.displayName(),
                membership.role().name());
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String refreshTokenSafe(String token) {
        return token.trim();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash identity token", exception);
        }
    }

    private BusinessException unauthorized(String message, String code) {
        return new BusinessException(message, HttpStatus.UNAUTHORIZED, code);
    }

    private String normalizeUsername(String username) {
        String normalized = username.trim();
        return normalized.contains("@")
                ? normalized.toLowerCase(Locale.ROOT)
                : normalized;
    }
}
