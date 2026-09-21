package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.AuthenticateIdentityCommand;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySessionApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySessionView;
import com.spaceagent.platform.identity.api.OrganizationApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.api.TenantView;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.identity.application.IdentityAuthenticationService;
import com.spaceagent.platform.identity.domain.ConsumedRefreshToken;
import com.spaceagent.platform.identity.domain.IdentityCredentialRepository;
import com.spaceagent.platform.identity.domain.IdentitySessionRepository;
import com.spaceagent.platform.identity.domain.RefreshTokenSession;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserCredential;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentitySessionRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String USERNAME = "identity-race@example.com";
    private static final String PASSWORD = "OriginalPassword123!";

    @Test
    void stalePasswordProofCannotCreateSessionAfterTheAccessVersionLock() {
        IdentityApplicationApi identities = mock(IdentityApplicationApi.class);
        OrganizationApplicationApi organizations = mock(OrganizationApplicationApi.class);
        IdentityCredentialRepository credentials = mock(IdentityCredentialRepository.class);
        IdentitySessionRepository sessions = mock(IdentitySessionRepository.class);
        IdentityActivityApplicationApi activity = mock(IdentityActivityApplicationApi.class);
        PasswordEncoder passwords = passwords();
        UserCredential verified = credential("old-password-hash");
        UserCredential changed = credential("new-password-hash");
        when(credentials.findByUsername(USERNAME)).thenReturn(Optional.of(verified));
        when(passwords.matches(PASSWORD, verified.passwordHash())).thenReturn(true);
        when(sessions.lockAccessVersion(USER_ID)).thenReturn(4L);
        when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(changed));

        IdentityAuthenticationService service = service(
                identities, organizations, credentials, sessions, activity, passwords);

        assertThatThrownBy(() -> service.authenticate(
                new AuthenticateIdentityCommand(USERNAME, PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(sessions, never()).saveRefreshToken(org.mockito.ArgumentMatchers.any());
        InOrder order = inOrder(credentials, sessions);
        order.verify(credentials).findByUsername(USERNAME);
        order.verify(sessions).lockAccessVersion(USER_ID);
        order.verify(credentials).findByUserId(USER_ID);
    }

    @Test
    void suspensionObservedAfterTheAccessVersionLockCannotCreateSession() {
        IdentityApplicationApi identities = mock(IdentityApplicationApi.class);
        OrganizationApplicationApi organizations = mock(OrganizationApplicationApi.class);
        IdentityCredentialRepository credentials = mock(IdentityCredentialRepository.class);
        IdentitySessionRepository sessions = mock(IdentitySessionRepository.class);
        IdentityActivityApplicationApi activity = mock(IdentityActivityApplicationApi.class);
        PasswordEncoder passwords = passwords();
        UserCredential credential = credential("current-password-hash");
        when(credentials.findByUsername(USERNAME)).thenReturn(Optional.of(credential));
        when(passwords.matches(PASSWORD, credential.passwordHash())).thenReturn(true);
        when(sessions.lockAccessVersion(USER_ID)).thenReturn(5L);
        when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
        when(activity.isUserActive(USER_ID)).thenReturn(false);

        IdentityAuthenticationService service = service(
                identities, organizations, credentials, sessions, activity, passwords);

        assertThatThrownBy(() -> service.authenticate(
                new AuthenticateIdentityCommand(USERNAME, PASSWORD)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(sessions, never()).saveRefreshToken(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void successfulLoginPersistsTheSessionWithTheLockedAccessVersion() {
        IdentityApplicationApi identities = mock(IdentityApplicationApi.class);
        OrganizationApplicationApi organizations = mock(OrganizationApplicationApi.class);
        IdentityCredentialRepository credentials = mock(IdentityCredentialRepository.class);
        IdentitySessionRepository sessions = mock(IdentitySessionRepository.class);
        IdentityActivityApplicationApi activity = mock(IdentityActivityApplicationApi.class);
        PasswordEncoder passwords = passwords();
        UserCredential credential = credential("current-password-hash");
        TenantMembershipView membership = membership();
        when(credentials.findByUsername(USERNAME)).thenReturn(Optional.of(credential));
        when(passwords.matches(PASSWORD, credential.passwordHash())).thenReturn(true);
        when(sessions.lockAccessVersion(USER_ID)).thenReturn(7L);
        when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
        when(activity.isUserActive(USER_ID)).thenReturn(true);
        when(identities.findUser(USER_ID)).thenReturn(Optional.of(user()));
        when(identities.findTenant(TENANT_ID)).thenReturn(Optional.of(tenant()));
        when(identities.findTenantMembership(TENANT_ID, USER_ID)).thenReturn(Optional.of(membership));

        IdentityAuthenticationService service = service(
                identities, organizations, credentials, sessions, activity, passwords);
        IdentitySessionView result = service.authenticate(
                new AuthenticateIdentityCommand(USERNAME, PASSWORD));

        var session = org.mockito.ArgumentCaptor.forClass(RefreshTokenSession.class);
        verify(sessions).saveRefreshToken(session.capture());
        assertThat(result.accessVersion()).isEqualTo(7L);
        assertThat(result.sessionId()).isEqualTo(session.getValue().sessionId());
        assertThat(session.getValue().accessVersion()).isEqualTo(7L);
        assertThat(session.getValue().tokenHash()).hasSize(64).isNotEqualTo(result.refreshToken());
        verify(activity).recordLoginSucceeded(USER_ID, USERNAME, "UNKNOWN");
    }

    @Test
    void refreshRacingLogoutCannotResurrectTheRevokedSession() throws Exception {
        IdentityApplicationApi identities = mock(IdentityApplicationApi.class);
        OrganizationApplicationApi organizations = mock(OrganizationApplicationApi.class);
        IdentityCredentialRepository credentials = mock(IdentityCredentialRepository.class);
        IdentityActivityApplicationApi activity = mock(IdentityActivityApplicationApi.class);
        PasswordEncoder passwords = passwords();
        PausingSessionRepository sessions = new PausingSessionRepository();
        UUID sessionId = UUID.randomUUID();
        String refreshToken = "initial-refresh-token";
        sessions.saveRefreshToken(new RefreshTokenSession(
                sha256(refreshToken), USER_ID, TENANT_ID, TenantRole.OWNER,
                NOW.plus(30, ChronoUnit.DAYS), NOW, sessionId, 0));
        when(identities.findTenantMembership(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(membership()));
        when(identities.findUser(USER_ID)).thenReturn(Optional.of(user()));
        when(credentials.findByUserId(USER_ID)).thenReturn(Optional.of(credential("current-password-hash")));
        IdentityAuthenticationService service = service(
                identities, organizations, credentials, sessions, activity, passwords);

        var executor = Executors.newSingleThreadExecutor();
        try {
            var rotation = executor.submit(() -> service.rotateRefreshToken(refreshToken));
            assertThat(sessions.consumed.await(2, TimeUnit.SECONDS)).isTrue();
            service.revokeSession(new IdentitySessionApplicationApi.SessionRevocationCommand(
                    USER_ID, sessionId, "current-access-token", NOW.plus(1, ChronoUnit.HOURS), null));
            sessions.continueRotation.countDown();
            IdentitySessionView rotated = rotation.get(2, TimeUnit.SECONDS);

            assertThat(service.isSessionActive(USER_ID, sessionId, 0)).isFalse();
            assertThat(service.isAccessTokenRevoked("current-access-token")).isTrue();
            assertThatThrownBy(() -> service.rotateRefreshToken(rotated.refreshToken()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo("REFRESH_TOKEN_INVALID"));
        } finally {
            sessions.continueRotation.countDown();
            executor.shutdownNow();
        }
    }

    private static IdentityAuthenticationService service(
            IdentityApplicationApi identities,
            OrganizationApplicationApi organizations,
            IdentityCredentialRepository credentials,
            IdentitySessionRepository sessions,
            IdentityActivityApplicationApi activity,
            PasswordEncoder passwords) {
        return new IdentityAuthenticationService(
                identities, organizations, credentials, sessions, activity, passwords,
                mock(IdGenerator.class), (TimeProvider) () -> NOW, 30);
    }

    private static PasswordEncoder passwords() {
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("dummy-password-hash");
        return passwords;
    }

    private static UserCredential credential(String hash) {
        return new UserCredential(USER_ID, USERNAME, hash, NOW, NOW);
    }

    private static UserView user() {
        return new UserView(USER_ID, TENANT_ID, USERNAME, "Identity Race", NOW);
    }

    private static TenantView tenant() {
        return new TenantView(TENANT_ID, "Identity", "identity", TenantStatus.ACTIVE, NOW);
    }

    private static TenantMembershipView membership() {
        return new TenantMembershipView(
                TENANT_ID, USER_ID, TenantRole.OWNER, TenantMembershipStatus.ACTIVE, NOW, NOW);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class PausingSessionRepository extends InMemoryIdentitySessionRepository {
        private final CountDownLatch consumed = new CountDownLatch(1);
        private final CountDownLatch continueRotation = new CountDownLatch(1);

        @Override
        public Optional<ConsumedRefreshToken> consumeRefreshToken(
                String tokenHash, String replacementHash, Instant consumedAt) {
            Optional<ConsumedRefreshToken> result = super.consumeRefreshToken(
                    tokenHash, replacementHash, consumedAt);
            if (result.isPresent()) {
                consumed.countDown();
                try {
                    if (!continueRotation.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for logout");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return result;
        }
    }
}
