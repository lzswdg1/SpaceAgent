package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.AcceptOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.AcceptedOrganizationInvitationView;
import com.spaceagent.platform.identity.api.CreateOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.OrganizationInvitationApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationInvitationCreatedView;
import com.spaceagent.platform.identity.api.OrganizationInvitationPreviewView;
import com.spaceagent.platform.identity.api.OrganizationInvitationView;
import com.spaceagent.platform.identity.api.OrganizationMembershipView;
import com.spaceagent.platform.identity.api.RevokeOrganizationInvitationCommand;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.OrganizationInvitation;
import com.spaceagent.platform.identity.domain.OrganizationInvitationRepository;
import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.UserIdentity;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@Transactional
public class OrganizationInvitationApplicationService
        implements OrganizationInvitationApplicationApi {

    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final long MAX_EXPIRY_HOURS = 24L * 30L;

    private final IdentityRepository identities;
    private final OrganizationInvitationRepository invitations;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final long defaultExpiryHours;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrganizationInvitationApplicationService(
            IdentityRepository identities,
            OrganizationInvitationRepository invitations,
            IdGenerator ids,
            TimeProvider time,
            @Value("${platform.identity.invitation-expiration-hours:168}") long defaultExpiryHours) {
        this.identities = identities;
        this.invitations = invitations;
        this.ids = ids;
        this.time = time;
        if (defaultExpiryHours < 1L || defaultExpiryHours > MAX_EXPIRY_HOURS) {
            throw new IllegalArgumentException("Invitation expiration must be between 1 and 720 hours");
        }
        this.defaultExpiryHours = defaultExpiryHours;
    }

    @Override
    public OrganizationInvitationCreatedView createInvitation(
            CreateOrganizationInvitationCommand command) {
        Tenant organization = requireActiveOrganization(command.organizationId(), true);
        TenantMembership actor = requireManager(organization.id(), command.actorUserId(), true);
        TenantRole role = requireInvitableRole(actor.role(), command.role());
        String email = normalizeEmail(command.email());
        rejectExistingMember(organization.id(), email);
        Instant now = time.now();
        OrganizationInvitation current = invitations
                .findPendingByOrganizationAndEmail(organization.id(), email)
                .orElse(null);
        if (current != null && current.isPendingAt(now)) {
            throw conflict("A pending invitation already exists", "ORGANIZATION_INVITATION_EXISTS");
        }
        if (current != null) {
            invitations.save(current.expire(now));
        }
        long expiresInHours = command.expiresInHours() == null
                ? defaultExpiryHours
                : command.expiresInHours();
        if (expiresInHours < 1L || expiresInHours > MAX_EXPIRY_HOURS) {
            throw invalid("expiresInHours must be between 1 and 720");
        }
        String token = generateToken();
        OrganizationInvitation invitation = new OrganizationInvitation(
                ids.nextId(), organization.id(), email, role,
                OrganizationInvitationStatus.PENDING, hashToken(token), actor.userId(),
                now.plus(expiresInHours, ChronoUnit.HOURS), null, now, now, null, null);
        try {
            invitations.save(invitation);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A pending invitation already exists", "ORGANIZATION_INVITATION_EXISTS");
        }
        return new OrganizationInvitationCreatedView(toView(invitation, now), token);
    }

    @Override
    public List<OrganizationInvitationView> listInvitations(
            String organizationId,
            String actorUserId) {
        requireActiveOrganization(organizationId, false);
        requireManager(organizationId, actorUserId, false);
        Instant now = time.now();
        return invitations.findByOrganization(organizationId).stream()
                .map(invitation -> toView(invitation, now))
                .toList();
    }

    @Override
    public OrganizationInvitationView revokeInvitation(
            RevokeOrganizationInvitationCommand command) {
        requireActiveOrganization(command.organizationId(), true);
        TenantMembership actor = requireManager(
                command.organizationId(), command.actorUserId(), true);
        OrganizationInvitation invitation = invitations.findByIdForUpdate(
                        command.organizationId(), command.invitationId())
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        Instant now = time.now();
        requireCanManageRole(actor.role(), invitation.role());
        if (!invitation.isPendingAt(now)) {
            throw conflict("Invitation is no longer pending", "ORGANIZATION_INVITATION_NOT_PENDING");
        }
        OrganizationInvitation revoked = invitation.revoke(now);
        invitations.save(revoked);
        return toView(revoked, now);
    }

    @Override
    public OrganizationInvitationPreviewView previewInvitation(String rawToken) {
        String tokenHash = hashToken(requireToken(rawToken));
        OrganizationInvitation invitation = invitations.findByTokenHash(tokenHash)
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        Instant now = time.now();
        Tenant organization = identities.findTenantById(invitation.organizationId())
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        return new OrganizationInvitationPreviewView(
                organization.name(), organization.slug(), maskEmail(invitation.email()),
                invitation.role(), invitation.effectiveStatus(now), invitation.expiresAt());
    }

    @Override
    public AcceptedOrganizationInvitationView acceptInvitation(
            AcceptOrganizationInvitationCommand command) {
        String tokenHash = hashToken(requireToken(command.token()));
        OrganizationInvitation snapshot = invitations.findByTokenHash(tokenHash)
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        Tenant organization = requireActiveOrganization(snapshot.organizationId(), true);
        OrganizationInvitation invitation = invitations.findByTokenHashForUpdate(tokenHash)
                .filter(current -> organization.id().equals(current.organizationId()))
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        Instant now = time.now();
        if (invitation.effectiveStatus(now) == OrganizationInvitationStatus.EXPIRED) {
            throw conflict("Invitation has expired", "ORGANIZATION_INVITATION_EXPIRED");
        }
        if (invitation.status() != OrganizationInvitationStatus.PENDING) {
            throw conflict("Invitation is no longer pending", "ORGANIZATION_INVITATION_NOT_PENDING");
        }
        UserIdentity user = identities.findUserById(requireText(command.userId(), "userId"))
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (!invitation.email().equals(normalizeExternalId(user.externalId()))) {
            throw new BusinessException(
                    "Invitation belongs to another identity",
                    HttpStatus.FORBIDDEN,
                    "ORGANIZATION_INVITATION_IDENTITY_MISMATCH");
        }
        TenantMembership current = identities
                .findMembershipForUpdate(organization.id(), user.id())
                .orElse(null);
        if (current != null && current.isActive()) {
            throw conflict("User is already an active member", "ORGANIZATION_MEMBER_EXISTS");
        }
        TenantMembership membership = current == null
                ? new TenantMembership(
                        organization.id(), user.id(), invitation.role(),
                        TenantMembershipStatus.ACTIVE, now, now)
                : current.activate(invitation.role(), now);
        identities.saveMembership(membership);
        OrganizationInvitation accepted = invitation.accept(user.id(), now);
        if (!invitations.markAcceptedIfPending(tokenHash, user.id(), now)) {
            throw conflict("Invitation is no longer pending", "ORGANIZATION_INVITATION_NOT_PENDING");
        }
        return new AcceptedOrganizationInvitationView(
                accepted.id(), organization.id(), toMembershipView(membership));
    }

    private void rejectExistingMember(String organizationId, String email) {
        boolean exists = identities.findUsersByExternalIdIgnoreCase(email).stream()
                .map(user -> identities.findMembership(organizationId, user.id()).orElse(null))
                .anyMatch(membership -> membership != null && membership.isActive());
        if (exists) {
            throw conflict("User is already an active member", "ORGANIZATION_MEMBER_EXISTS");
        }
    }

    private Tenant requireActiveOrganization(String organizationId, boolean forUpdate) {
        Tenant organization = (forUpdate
                ? identities.findTenantByIdForUpdate(requireText(organizationId, "organizationId"))
                : identities.findTenantById(requireText(organizationId, "organizationId")))
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        if (!organization.isActive()) {
            throw conflict("Organization is not active", "ORGANIZATION_NOT_ACTIVE");
        }
        return organization;
    }

    private TenantMembership requireManager(
            String organizationId,
            String userId,
            boolean forUpdate) {
        TenantMembership membership = (forUpdate
                ? identities.findMembershipForUpdate(organizationId, requireText(userId, "userId"))
                : identities.findMembership(organizationId, requireText(userId, "userId")))
                .filter(TenantMembership::isActive)
                .orElseThrow(OrganizationInvitationApplicationService::notFound);
        if (membership.role() != TenantRole.OWNER && membership.role() != TenantRole.ADMIN) {
            throw accessDenied();
        }
        return membership;
    }

    private static TenantRole requireInvitableRole(TenantRole actorRole, TenantRole role) {
        if (role == null) {
            throw invalid("Invitation role is required");
        }
        if (role == TenantRole.OWNER) {
            throw conflict("Use ownership transfer to assign OWNER", "ORGANIZATION_USE_OWNER_TRANSFER");
        }
        requireCanManageRole(actorRole, role);
        return role;
    }

    private static void requireCanManageRole(TenantRole actorRole, TenantRole targetRole) {
        if (actorRole == TenantRole.OWNER) {
            return;
        }
        if (actorRole == TenantRole.ADMIN
                && (targetRole == TenantRole.MEMBER || targetRole == TenantRole.VIEWER)) {
            return;
        }
        throw accessDenied();
    }

    private static String normalizeEmail(String value) {
        String email = normalizeExternalId(requireText(value, "email"));
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw invalid("A valid email with at most 255 characters is required");
        }
        return email;
    }

    private static String normalizeExternalId(String value) {
        return requireText(value, "externalId").toLowerCase(Locale.ROOT);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireToken(String value) {
        String token = requireText(value, "token");
        if (token.length() < 32 || token.length() > 512) {
            throw notFound();
        }
        return token;
    }

    private static String hashToken(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash invitation token", exception);
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        return local.substring(0, 1) + "***" + email.substring(at);
    }

    private static OrganizationInvitationView toView(
            OrganizationInvitation invitation,
            Instant now) {
        return new OrganizationInvitationView(
                invitation.id(), invitation.organizationId(), invitation.email(), invitation.role(),
                invitation.effectiveStatus(now), invitation.invitedByUserId(), invitation.expiresAt(),
                invitation.acceptedByUserId(), invitation.createdAt(), invitation.updatedAt(),
                invitation.acceptedAt(), invitation.revokedAt());
    }

    private static OrganizationMembershipView toMembershipView(TenantMembership membership) {
        return new OrganizationMembershipView(
                membership.tenantId(), membership.userId(), membership.role(),
                membership.status(), membership.joinedAt(), membership.updatedAt());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value.trim();
    }

    private static BusinessException accessDenied() {
        return new BusinessException(
                "Organization manager role required",
                HttpStatus.FORBIDDEN,
                "ORGANIZATION_ACCESS_DENIED");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "Organization invitation not found",
                HttpStatus.NOT_FOUND,
                "ORGANIZATION_INVITATION_NOT_FOUND");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }
}
