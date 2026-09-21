package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.AcceptOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.AddOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.api.RevokeOrganizationInvitationCommand;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationInvitationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryOrganizationInvitationRepository;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryOrganizationCleanupRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformOrganizationInvitationApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private InMemoryIdentityRepository identities;
    private InMemoryOrganizationInvitationRepository repository;
    private OrganizationApplicationService organizations;
    private OrganizationInvitationApplicationService invitations;

    @BeforeEach
    void setUp() {
        identities = new InMemoryIdentityRepository();
        repository = new InMemoryOrganizationInvitationRepository();
        UuidGenerator ids = new UuidGenerator();
        var cleanup = new OrganizationCleanupApplicationService(
                identities, new InMemoryOrganizationCleanupRepository(), clock::get, 0, 10, 1);
        organizations = new OrganizationApplicationService(identities, ids, clock::get, cleanup);
        invitations = new OrganizationInvitationApplicationService(
                identities, repository, ids, clock::get, 168L);
    }

    @Test
    void tokenIsReturnedOnceHashedAtRestAndAcceptsOnlyTheBoundIdentity() {
        var owner = provision("owner@example.com", "owner-personal");
        var invitee = provision("Invitee@Example.com", "invitee-personal");
        var stranger = provision("stranger@example.com", "stranger-personal");
        String organizationId = createOrganization(owner.user().id(), "invitation-target");

        var created = invitations.createInvitation(new CreateOrganizationInvitationCommand(
                organizationId, owner.user().id(), "INVITEE@example.com", TenantRole.MEMBER, 24L));

        assertEquals("invitee@example.com", created.invitation().email());
        assertEquals(43, created.token().length());
        assertFalse(repository.findByTokenHash(created.token()).isPresent());
        var stored = repository.findByTokenHash(sha256(created.token())).orElseThrow();
        assertNotEquals(created.token(), stored.tokenHash());
        assertEquals("i***@example.com",
                invitations.previewInvitation(created.token()).maskedEmail());

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> invitations.acceptInvitation(new AcceptOrganizationInvitationCommand(
                        created.token(), stranger.user().id())));
        assertEquals(HttpStatus.FORBIDDEN, mismatch.getStatus());
        assertEquals("ORGANIZATION_INVITATION_IDENTITY_MISMATCH", mismatch.getCode());

        var accepted = invitations.acceptInvitation(new AcceptOrganizationInvitationCommand(
                created.token(), invitee.user().id()));
        assertEquals(TenantRole.MEMBER, accepted.membership().role());
        assertTrue(identities.findMembership(organizationId, invitee.user().id())
                .orElseThrow().isActive());
        assertEquals(OrganizationInvitationStatus.ACCEPTED,
                repository.findByTokenHash(sha256(created.token())).orElseThrow().status());

        BusinessException replay = assertThrows(BusinessException.class,
                () -> invitations.acceptInvitation(new AcceptOrganizationInvitationCommand(
                        created.token(), invitee.user().id())));
        assertEquals("ORGANIZATION_INVITATION_NOT_PENDING", replay.getCode());
    }

    @Test
    void duplicateExistingExpiredRevokedAndRoleBoundariesFailClosed() {
        var owner = provision("owner-two@example.com", "owner-two-personal");
        var admin = provision("admin@example.com", "admin-personal");
        var member = provision("member@example.com", "member-personal");
        var invitee = provision("new@example.com", "new-personal");
        String organizationId = createOrganization(owner.user().id(), "invitation-policies");
        organizations.addMember(new AddOrganizationMemberCommand(
                owner.user().id(), organizationId, admin.user().id(), TenantRole.ADMIN));
        organizations.addMember(new AddOrganizationMemberCommand(
                owner.user().id(), organizationId, member.user().id(), TenantRole.MEMBER));

        assertCode("ORGANIZATION_MEMBER_EXISTS", () -> invitations.createInvitation(
                new CreateOrganizationInvitationCommand(
                        organizationId, owner.user().id(), "member@example.com",
                        TenantRole.MEMBER, null)));
        assertCode("ORGANIZATION_ACCESS_DENIED", () -> invitations.createInvitation(
                new CreateOrganizationInvitationCommand(
                        organizationId, member.user().id(), "other@example.com",
                        TenantRole.MEMBER, null)));
        assertCode("ORGANIZATION_ACCESS_DENIED", () -> invitations.createInvitation(
                new CreateOrganizationInvitationCommand(
                        organizationId, admin.user().id(), "other@example.com",
                        TenantRole.ADMIN, null)));

        var pending = invitations.createInvitation(new CreateOrganizationInvitationCommand(
                organizationId, owner.user().id(), "new@example.com", TenantRole.ADMIN, 1L));
        assertCode("ORGANIZATION_INVITATION_EXISTS", () -> invitations.createInvitation(
                new CreateOrganizationInvitationCommand(
                        organizationId, owner.user().id(), "new@example.com",
                        TenantRole.MEMBER, null)));
        assertCode("ORGANIZATION_ACCESS_DENIED", () -> invitations.revokeInvitation(
                new RevokeOrganizationInvitationCommand(
                        organizationId, pending.invitation().id(), admin.user().id())));

        clock.set(NOW.plusSeconds(3600));
        assertEquals(OrganizationInvitationStatus.EXPIRED,
                invitations.previewInvitation(pending.token()).status());
        assertCode("ORGANIZATION_INVITATION_EXPIRED", () -> invitations.acceptInvitation(
                new AcceptOrganizationInvitationCommand(pending.token(), invitee.user().id())));

        var replacement = invitations.createInvitation(new CreateOrganizationInvitationCommand(
                organizationId, owner.user().id(), "new@example.com", TenantRole.MEMBER, null));
        var revoked = invitations.revokeInvitation(new RevokeOrganizationInvitationCommand(
                organizationId, replacement.invitation().id(), owner.user().id()));
        assertEquals(OrganizationInvitationStatus.REVOKED, revoked.status());
        assertCode("ORGANIZATION_INVITATION_NOT_PENDING", () -> invitations.acceptInvitation(
                new AcceptOrganizationInvitationCommand(replacement.token(), invitee.user().id())));

        var race = provision("race@example.com", "race-personal");
        var raceInvitation = invitations.createInvitation(new CreateOrganizationInvitationCommand(
                organizationId, owner.user().id(), "race@example.com", TenantRole.VIEWER, null));
        organizations.addMember(new AddOrganizationMemberCommand(
                owner.user().id(), organizationId, race.user().id(), TenantRole.MEMBER));
        assertCode("ORGANIZATION_MEMBER_EXISTS", () -> invitations.acceptInvitation(
                new AcceptOrganizationInvitationCommand(
                        raceInvitation.token(), race.user().id())));
        assertEquals(TenantRole.MEMBER, identities.findMembership(
                organizationId, race.user().id()).orElseThrow().role());
    }

    private com.spaceagent.platform.identity.api.ProvisionedOrganizationView provision(
            String externalId,
            String slug) {
        return organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        externalId, externalId, "Personal " + externalId, slug));
    }

    private String createOrganization(String ownerId, String slug) {
        return organizations.createOrganization(new CreateOrganizationCommand(
                ownerId, "Invitation Target", slug)).id();
    }

    private static void assertCode(
            String code,
            org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(code, exception.getCode());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
