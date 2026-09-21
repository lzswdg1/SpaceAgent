package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.AuthenticatedIdentityView;
import com.spaceagent.platform.identity.api.IdentityActivationApplicationApi;
import com.spaceagent.platform.identity.api.IdentityUserAdministrationApi;
import com.spaceagent.platform.identity.api.OrganizationApplicationApi;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.domain.IdentityCredentialRepository;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.IdentitySessionRepository;
import com.spaceagent.platform.identity.domain.IdentityUserAdministrationRepository;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.UserCredential;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class IdentityUserAdministrationService
        implements IdentityUserAdministrationApi, IdentityActivationApplicationApi,
        com.spaceagent.platform.identity.api.IdentityPasswordResetApi {
    private final IdentityRepository identityRepository;
    private final IdentityUserAdministrationRepository administrationRepository;
    private final IdentityCredentialRepository credentialRepository;
    private final IdentitySessionRepository sessionRepository;
    private final OrganizationApplicationApi organizationApi;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public IdentityUserAdministrationService(
            IdentityRepository identityRepository,
            IdentityUserAdministrationRepository administrationRepository,
            IdentityCredentialRepository credentialRepository,
            IdentitySessionRepository sessionRepository,
            OrganizationApplicationApi organizationApi,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.identityRepository = identityRepository;
        this.administrationRepository = administrationRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.organizationApi = organizationApi;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public CreatedPendingUser createPendingUser(CreatePendingUserCommand command) {
        String login = normalizeLogin(command.loginName());
        if (!identityRepository.findUsersByExternalIdIgnoreCase(login).isEmpty()
                || credentialRepository.findByUsername(login).isPresent()) {
            throw new BusinessException("User login already exists", HttpStatus.CONFLICT,
                    "SYSTEM_ADMIN_USER_LOGIN_EXISTS");
        }
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? login : command.displayName().trim();
        String organizationName = command.organizationName() == null || command.organizationName().isBlank()
                ? "Personal - " + displayName : command.organizationName().trim();
        String slug = command.organizationSlug() == null || command.organizationSlug().isBlank()
                ? "personal-" + idGenerator.nextId().replace("-", "").substring(0, 16)
                : command.organizationSlug().trim().toLowerCase(Locale.ROOT);
        var provisioned = organizationApi.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(login, displayName, organizationName, slug));
        Instant now = timeProvider.now();
        if (!administrationRepository.updateStatus(
                provisioned.user().id(), "ACTIVE", "PENDING_ACTIVATION", now)) {
            throw new IllegalStateException("Unable to initialize pending User status");
        }
        String rawToken = randomToken();
        Instant expiresAt = now.plus(24, ChronoUnit.HOURS);
        administrationRepository.saveActivationToken(
                new IdentityUserAdministrationRepository.ActivationToken(
                        UUID.randomUUID(), provisioned.user().id(), hash(rawToken), expiresAt,
                        null, now, UUID.fromString(command.actorId())));
        return new CreatedPendingUser(provisioned.user().id(), provisioned.organization().id(),
                "PENDING_ACTIVATION", rawToken, expiresAt);
    }

    @Override
    public UserLifecycleResult suspendUser(UserLifecycleCommand command) {
        Instant now = timeProvider.now();
        requireReason(command.reason());
        if (!administrationRepository.updateStatus(command.userId(), "ACTIVE", "SUSPENDED", now)) {
            throw lifecycleConflict(command.userId(), "suspend");
        }
        sessionRepository.revokeAllRefreshTokens(command.userId(), now);
        sessionRepository.incrementAccessVersion(command.userId());
        return new UserLifecycleResult(command.userId(), "SUSPENDED", now, true);
    }

    @Override
    public UserLifecycleResult restoreUser(UserLifecycleCommand command) {
        Instant now = timeProvider.now();
        requireReason(command.reason());
        if (!administrationRepository.updateStatus(command.userId(), "SUSPENDED", "ACTIVE", now)) {
            throw lifecycleConflict(command.userId(), "restore");
        }
        return new UserLifecycleResult(command.userId(), "ACTIVE", now, false);
    }

    @Override
    public UserLifecycleResult updateUser(UserProfileCommand command) {
        requireReason(command.reason());
        if (command.displayName() == null || command.displayName().isBlank() || command.displayName().length() > 120)
            throw new BusinessException("Display name must contain 1 to 120 characters", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_USER_NAME_INVALID");
        var user = requireManageableUser(command.userId());
        Instant now = timeProvider.now();
        identityRepository.updateUserDisplayName(command.userId(), command.displayName().trim());
        return new UserLifecycleResult(command.userId(), user.status(), now, false);
    }

    @Override
    public UserLifecycleResult revokeSessions(UserLifecycleCommand command) {
        requireReason(command.reason());
        var user = requireManageableUser(command.userId());
        Instant now = timeProvider.now();
        sessionRepository.incrementAccessVersion(command.userId());
        sessionRepository.revokeAllRefreshTokens(command.userId(), now);
        return new UserLifecycleResult(command.userId(), user.status(), now, true);
    }

    @Override public PasswordReset issuePasswordReset(UserLifecycleCommand command) {
        requireReason(command.reason());
        var user = requireManageableUser(command.userId());
        if (!"ACTIVE".equals(user.status()) || credentialRepository.findByUserId(user.id()).isEmpty())
            throw lifecycleConflict(user.id(), "reset");
        String raw = randomToken();
        Instant now = timeProvider.now();
        Instant expires = now.plusSeconds(900);
        sessionRepository.incrementAccessVersion(user.id());
        administrationRepository.savePasswordResetToken(new IdentityUserAdministrationRepository.PasswordResetToken(
                UUID.randomUUID(), user.id(), hash(raw), expires, null, now, UUID.fromString(command.actorId()),
                sessionRepository.accessVersion(user.id())));
        sessionRepository.revokeAllRefreshTokens(user.id(), now);
        return new PasswordReset(user.id(), raw, expires);
    }

    @Override @Transactional public void resetPassword(String resetToken, String newPassword) {
        if (resetToken == null || resetToken.length() > 200 || newPassword == null
                || newPassword.length() < 10 || newPassword.length() > 64)
            throw new BusinessException("Invalid password reset request", HttpStatus.BAD_REQUEST, "PASSWORD_RESET_INVALID");
        var token = administrationRepository.findPasswordResetToken(hash(resetToken))
                .filter(t -> t.usableAt(timeProvider.now())).orElseThrow(this::invalidPasswordReset);
        // User first, then token: identical lock ordering to reset issuance and refresh rotation.
        if (sessionRepository.lockAccessVersion(token.userId()) != token.accessVersion())
            throw invalidPasswordReset();
        var user = administrationRepository.findUser(token.userId())
                .filter(u -> "ACTIVE".equals(u.status())).orElseThrow(this::invalidPasswordReset);
        if (!administrationRepository.consumePasswordResetToken(token.id(), timeProvider.now()))
            throw invalidPasswordReset();
        credentialRepository.updatePassword(user.id(), passwordEncoder.encode(newPassword));
        sessionRepository.incrementAccessVersion(user.id());
        sessionRepository.revokeAllRefreshTokens(user.id(), timeProvider.now());
    }

    private BusinessException invalidPasswordReset() {
        return new BusinessException("Password reset token is invalid or expired", HttpStatus.UNAUTHORIZED,
                "PASSWORD_RESET_TOKEN_INVALID");
    }

    private IdentityUserAdministrationRepository.UserLifecycleRow requireManageableUser(String id) {
        var user = administrationRepository.findUser(id).orElseThrow(() ->
                new BusinessException("User not found", HttpStatus.NOT_FOUND, "SYSTEM_ADMIN_USER_NOT_FOUND"));
        sessionRepository.lockAccessVersion(id);
        user = administrationRepository.findUser(id).orElseThrow();
        if (!java.util.Set.of("ACTIVE", "SUSPENDED", "PENDING_ACTIVATION").contains(user.status()))
            throw lifecycleConflict(id, "manage");
        return user;
    }

    @Override
    @Transactional
    public AuthenticatedIdentityView activate(String activationToken, String password) {
        if (activationToken == null || activationToken.isBlank()) {
            throw invalidActivation();
        }
        if (password == null || password.length() < 10 || password.length() > 64) {
            throw new BusinessException("Password must be between 10 and 64 characters",
                    HttpStatus.BAD_REQUEST, "ACTIVATION_PASSWORD_INVALID");
        }
        Instant now = timeProvider.now();
        var token = administrationRepository.findActivationTokenForUpdate(hash(activationToken.trim()))
                .filter(value -> value.usableAt(now)).orElseThrow(this::invalidActivation);
        var user = administrationRepository.findUser(token.userId())
                .filter(value -> "PENDING_ACTIVATION".equals(value.status()))
                .orElseThrow(this::invalidActivation);
        credentialRepository.save(new UserCredential(user.id(), normalizeLogin(user.externalId()),
                passwordEncoder.encode(password), now, now));
        if (!administrationRepository.updateStatus(user.id(), "PENDING_ACTIVATION", "ACTIVE", now)
                || !administrationRepository.consumeActivationToken(token.id(), now)) {
            throw invalidActivation();
        }
        var membership = identityRepository.findMembership(user.tenantId(), user.id())
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(this::invalidActivation);
        return new AuthenticatedIdentityView(user.id(), user.tenantId(), user.externalId(),
                user.displayName(), membership.role().name());
    }

    private BusinessException lifecycleConflict(String userId, String action) {
        var current = administrationRepository.findUser(userId)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND,
                        "SYSTEM_ADMIN_USER_NOT_FOUND"));
        return new BusinessException("User cannot be " + action + "d from status " + current.status(),
                HttpStatus.CONFLICT, "SYSTEM_ADMIN_USER_STATUS_CONFLICT");
    }

    private BusinessException invalidActivation() {
        return new BusinessException("Activation token is invalid or expired",
                HttpStatus.UNAUTHORIZED, "ACTIVATION_TOKEN_INVALID");
    }

    private static String normalizeLogin(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new BusinessException("User login is invalid", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_USER_LOGIN_INVALID");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("Administrator reason is required", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_REASON_REQUIRED");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash activation token", error);
        }
    }
}
