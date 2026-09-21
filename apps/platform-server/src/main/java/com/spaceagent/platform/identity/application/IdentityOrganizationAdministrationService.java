package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.IdentityOrganizationAdministrationApi;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.IdentityUserAdministrationRepository;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class IdentityOrganizationAdministrationService
        implements IdentityOrganizationAdministrationApi {
    private static final Pattern SLUG = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final IdentityRepository identities;
    private final IdentityUserAdministrationRepository users;
    private final OrganizationCleanupApplicationApi cleanup;
    private final IdGenerator ids;
    private final TimeProvider time;

    public IdentityOrganizationAdministrationService(
            IdentityRepository identities,
            IdentityUserAdministrationRepository users,
            OrganizationCleanupApplicationApi cleanup,
            IdGenerator ids,
            TimeProvider time) {
        this.identities = identities;
        this.users = users;
        this.cleanup = cleanup;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrganizationResult create(CreateOrganizationCommand command) {
        requireReason(command.reason());
        var owner = users.findUser(required(command.ownerUserId(), "ownerUserId"))
                .orElseThrow(() -> notFound("SYSTEM_ADMIN_USER_NOT_FOUND", "User not found"));
        if (!"ACTIVE".equals(owner.status())) {
            throw new BusinessException("Organization owner must be active", HttpStatus.CONFLICT,
                    "SYSTEM_ADMIN_ORGANIZATION_OWNER_NOT_ACTIVE");
        }
        String name = name(command.name());
        String slug = slug(command.slug());
        rejectDuplicateSlug(slug, null);
        Instant now = time.now();
        Tenant organization = new Tenant(ids.nextId(), name, slug, TenantStatus.ACTIVE,
                owner.id(), now, now, null);
        try {
            identities.saveTenant(organization);
            identities.saveMembership(new TenantMembership(organization.id(), owner.id(),
                    TenantRole.OWNER, TenantMembershipStatus.ACTIVE, now, now));
        } catch (DataIntegrityViolationException error) {
            throw slugConflict();
        }
        return result(organization);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrganizationResult update(UpdateOrganizationCommand command) {
        requireReason(command.reason());
        Tenant current = activeForUpdate(command.organizationId());
        String name = name(command.name());
        String slug = slug(command.slug());
        rejectDuplicateSlug(slug, current.id());
        Tenant updated = current.updateMetadata(name, slug, time.now());
        try {
            identities.saveTenant(updated);
        } catch (DataIntegrityViolationException error) {
            throw slugConflict();
        }
        return result(updated);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrganizationResult requestDeletion(DeleteOrganizationCommand command) {
        requireReason(command.reason());
        String organizationId = required(command.organizationId(), "organizationId");
        Tenant current = identities.findTenantByIdForUpdate(organizationId)
                .orElseThrow(() -> notFound("ORGANIZATION_NOT_FOUND", "Organization not found"));
        if (current.status() == TenantStatus.DELETING) {
            cleanup.resumeStorageDeletionAfterAdminApproval(organizationId);
            return result(current);
        }
        if (current.status() != TenantStatus.ACTIVE) {
            throw new BusinessException("Organization cannot be deleted from its current state",
                    HttpStatus.CONFLICT, "SYSTEM_ADMIN_ORGANIZATION_STATUS_CONFLICT");
        }
        Tenant deleting = current.markDeleting(time.now());
        identities.saveTenant(deleting);
        cleanup.enqueue(deleting.id());
        return result(deleting);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public MembershipResult addMember(AddMemberCommand command) {
        requireReason(command.reason());
        Tenant organization = activeForUpdate(command.organizationId());
        String userId = activeUser(command.userId());
        TenantRole role = memberRole(command.role());
        TenantMembership current = identities.findMembershipForUpdate(organization.id(), userId)
                .orElse(null);
        if (current != null && current.isActive()) {
            throw new BusinessException("User is already an active Organization member",
                    HttpStatus.CONFLICT, "SYSTEM_ADMIN_ORGANIZATION_MEMBER_EXISTS");
        }
        Instant now = time.now();
        TenantMembership membership = current == null
                ? new TenantMembership(organization.id(), userId, role,
                        TenantMembershipStatus.ACTIVE, now, now)
                : current.activate(role, now);
        identities.saveMembership(membership);
        return membershipResult(membership);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public MembershipResult updateMemberRole(UpdateMemberRoleCommand command) {
        requireReason(command.reason());
        Tenant organization = activeForUpdate(command.organizationId());
        String userId = activeUser(command.userId());
        TenantRole role = memberRole(command.role());
        TenantMembership current = activeMembership(organization.id(), userId);
        if (current.role() == TenantRole.OWNER) {
            throw ownerTransferRequired();
        }
        TenantMembership updated = current.changeRole(role, time.now());
        identities.saveMembership(updated);
        return membershipResult(updated);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public MembershipResult removeMember(RemoveMemberCommand command) {
        requireReason(command.reason());
        Tenant organization = activeForUpdate(command.organizationId());
        TenantMembership current = activeMembership(organization.id(), command.userId());
        if (current.role() == TenantRole.OWNER) {
            throw ownerTransferRequired();
        }
        TenantMembership removed = current.suspend(time.now());
        identities.saveMembership(removed);
        return membershipResult(removed);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrganizationResult transferOwnership(TransferOwnershipCommand command) {
        requireReason(command.reason());
        Tenant organization = activeForUpdate(command.organizationId());
        String newOwnerUserId = activeUser(command.newOwnerUserId());
        if (newOwnerUserId.equals(organization.creatorUserId())) {
            return result(organization);
        }
        TenantMembership currentOwner = activeMembership(
                organization.id(), organization.creatorUserId());
        if (currentOwner.role() != TenantRole.OWNER) {
            throw new IllegalStateException("Organization creator is not the active OWNER");
        }
        TenantMembership newOwner = activeMembership(organization.id(), newOwnerUserId);
        Instant now = time.now();
        identities.saveMembership(currentOwner.changeRole(TenantRole.ADMIN, now));
        identities.saveMembership(newOwner.changeRole(TenantRole.OWNER, now));
        Tenant transferred = organization.assignCreator(newOwnerUserId, now);
        identities.saveTenant(transferred);
        return result(transferred);
    }

    private Tenant activeForUpdate(String organizationId) {
        Tenant organization = identities.findTenantByIdForUpdate(
                        required(organizationId, "organizationId"))
                .orElseThrow(() -> notFound("ORGANIZATION_NOT_FOUND", "Organization not found"));
        if (organization.status() != TenantStatus.ACTIVE) {
            throw new BusinessException("Organization is not active", HttpStatus.CONFLICT,
                    "ORGANIZATION_NOT_ACTIVE");
        }
        return organization;
    }

    private String activeUser(String userId) {
        var user = users.findUser(required(userId, "userId"))
                .orElseThrow(() -> notFound("SYSTEM_ADMIN_USER_NOT_FOUND", "User not found"));
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException("Organization member must be active", HttpStatus.CONFLICT,
                    "SYSTEM_ADMIN_ORGANIZATION_MEMBER_NOT_ACTIVE");
        }
        return user.id();
    }

    private TenantMembership activeMembership(String organizationId, String userId) {
        return identities.findMembershipForUpdate(organizationId, required(userId, "userId"))
                .filter(TenantMembership::isActive)
                .orElseThrow(() -> notFound("SYSTEM_ADMIN_ORGANIZATION_MEMBER_NOT_FOUND",
                        "Organization member not found"));
    }

    private void rejectDuplicateSlug(String slug, String currentId) {
        identities.findTenantBySlug(slug).filter(value -> !value.id().equals(currentId))
                .ifPresent(value -> { throw slugConflict(); });
    }

    private OrganizationResult result(Tenant organization) {
        return new OrganizationResult(organization.id(), organization.name(), organization.slug(),
                organization.creatorUserId(), organization.status().name(),
                identities.countActiveMemberships(organization.id()), organization.createdAt(),
                organization.updatedAt(), organization.deletionRequestedAt());
    }

    private static MembershipResult membershipResult(TenantMembership membership) {
        return new MembershipResult(membership.tenantId(), membership.userId(),
                membership.role().name(), membership.status().name(), membership.joinedAt(),
                membership.updatedAt());
    }

    private static TenantRole memberRole(String value) {
        String normalized = required(value, "role").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ADMIN" -> TenantRole.ADMIN;
            case "MEMBER" -> TenantRole.MEMBER;
            case "VIEWER" -> TenantRole.VIEWER;
            case "OWNER" -> throw ownerTransferRequired();
            default -> throw invalid("Organization member role is invalid");
        };
    }

    private static String name(String value) {
        String normalized = required(value, "name");
        if (normalized.length() > 120) {
            throw invalid("Organization name must not exceed 120 characters");
        }
        return normalized;
    }

    private static String slug(String value) {
        String normalized = required(value, "slug").toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(normalized).matches()) {
            throw invalid("Organization slug is invalid");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " is required");
        return value.trim();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("Administrator reason is required", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_REASON_REQUIRED");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST,
                "SYSTEM_ADMIN_ORGANIZATION_INVALID");
    }

    private static BusinessException slugConflict() {
        return new BusinessException("Organization slug already exists", HttpStatus.CONFLICT,
                "ORGANIZATION_SLUG_CONFLICT");
    }

    private static BusinessException ownerTransferRequired() {
        return new BusinessException("Use explicit ownership transfer for OWNER",
                HttpStatus.CONFLICT, "SYSTEM_ADMIN_ORGANIZATION_OWNER_TRANSFER_REQUIRED");
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(message, HttpStatus.NOT_FOUND, code);
    }
}
