package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.AddOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.OrganizationApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationLeaveView;
import com.spaceagent.platform.identity.api.OrganizationMembershipView;
import com.spaceagent.platform.identity.api.OrganizationSummaryView;
import com.spaceagent.platform.identity.api.OrganizationView;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.api.ProvisionedOrganizationView;
import com.spaceagent.platform.identity.api.RemoveOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.TransferOrganizationOwnershipCommand;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserIdentity;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Product Organization lifecycle over the existing Tenant persistence boundary. */
@Service
public class OrganizationApplicationService implements OrganizationApplicationApi {

    private static final Pattern SLUG = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final IdentityRepository repository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final OrganizationCleanupApplicationApi cleanupApi;

    public OrganizationApplicationService(
            IdentityRepository repository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            OrganizationCleanupApplicationApi cleanupApi) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.cleanupApi = cleanupApi;
    }

    @Override
    public ProvisionedOrganizationView provisionPersonalOrganization(
            ProvisionPersonalOrganizationCommand command) {
        String externalId = requireText(command.externalId(), "externalId");
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? externalId
                : command.displayName().trim();
        String organizationName = normalizeName(command.organizationName());
        String slug = normalizeSlug(command.organizationSlug());
        rejectDuplicateSlug(slug);

        Instant now = timeProvider.now();
        String organizationId = idGenerator.nextId();
        String userId = idGenerator.nextId();
        Tenant bootstrap = new Tenant(
                organizationId, organizationName, slug, TenantStatus.ACTIVE,
                null, now, now, null);
        repository.saveTenant(bootstrap);
        UserIdentity user = new UserIdentity(
                userId, organizationId, externalId, displayName, now, now);
        repository.saveUser(user);
        Tenant organization = bootstrap.assignCreator(userId, now);
        repository.saveTenant(organization);
        TenantMembership membership = new TenantMembership(
                organizationId, userId, TenantRole.OWNER,
                TenantMembershipStatus.ACTIVE, now, now);
        repository.saveMembership(membership);
        return new ProvisionedOrganizationView(
                toView(organization), toView(user), toView(membership));
    }

    @Override
    @Transactional
    public OrganizationView createOrganization(CreateOrganizationCommand command) {
        UserIdentity actor = requireUser(command.actorUserId());
        String name = normalizeName(command.name());
        String slug = normalizeSlug(command.slug());
        rejectDuplicateSlug(slug);
        Instant now = timeProvider.now();
        Tenant organization = new Tenant(
                idGenerator.nextId(), name, slug, TenantStatus.ACTIVE,
                actor.id(), now, now, null);
        try {
            repository.saveTenant(organization);
            repository.saveMembership(new TenantMembership(
                    organization.id(), actor.id(), TenantRole.OWNER,
                    TenantMembershipStatus.ACTIVE, now, now));
        } catch (DataIntegrityViolationException exception) {
            throw slugConflict();
        }
        return toView(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSummaryView> listOrganizations(String userId) {
        requireUser(userId);
        List<TenantMembership> memberships = repository.findMembershipsByUserId(userId).stream()
                .filter(TenantMembership::isActive).toList();
        java.util.Map<String, Tenant> tenants = repository.findTenantsByIds(
                        memberships.stream().map(TenantMembership::tenantId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Tenant::id, value -> value));
        return memberships.stream()
                .map(membership -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                        membership, tenants.get(membership.tenantId())))
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().status() != TenantStatus.DELETED)
                .map(entry -> new OrganizationSummaryView(
                        toView(entry.getValue()), toView(entry.getKey())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationView getOrganization(String organizationId, String userId) {
        Tenant organization = requireVisibleOrganization(organizationId, userId);
        return toView(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationMembershipView> listMembers(
            String organizationId,
            String userId) {
        requireVisibleOrganization(organizationId, userId);
        return repository.findMembershipsByTenantId(organizationId).stream()
                .filter(TenantMembership::isActive)
                .map(OrganizationApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional
    public OrganizationMembershipView addMember(AddOrganizationMemberCommand command) {
        Tenant organization = requireActiveOrganizationForUpdate(command.organizationId());
        TenantMembership actor = requireActiveMembership(
                organization.id(), command.actorUserId(), true);
        requireUser(command.memberUserId());
        TenantRole requestedRole = requireAssignableRole(command.role());
        requireCanManageRole(actor.role(), requestedRole);
        Instant now = timeProvider.now();
        TenantMembership current = repository
                .findMembershipForUpdate(organization.id(), command.memberUserId())
                .orElse(null);
        if (current != null && current.role() == TenantRole.OWNER) {
            throw ownerImmutable();
        }
        if (actor.role() == TenantRole.ADMIN
                && current != null && current.role() == TenantRole.ADMIN) {
            throw accessDenied();
        }
        TenantMembership updated = current == null
                ? new TenantMembership(
                        organization.id(), command.memberUserId(), requestedRole,
                        TenantMembershipStatus.ACTIVE, now, now)
                : current.activate(requestedRole, now);
        repository.saveMembership(updated);
        return toView(updated);
    }

    @Override
    @Transactional
    public void removeMember(RemoveOrganizationMemberCommand command) {
        if (command.actorUserId().equals(command.memberUserId())) {
            throw new BusinessException(
                    "Use leave Organization for self-removal",
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_USE_LEAVE");
        }
        Tenant organization = requireActiveOrganizationForUpdate(command.organizationId());
        TenantMembership actor = requireActiveMembership(
                organization.id(), command.actorUserId(), true);
        TenantMembership target = requireActiveMembership(
                organization.id(), command.memberUserId(), true);
        if (target.role() == TenantRole.OWNER) {
            throw ownerImmutable();
        }
        if (actor.role() == TenantRole.ADMIN && target.role() == TenantRole.ADMIN) {
            throw accessDenied();
        }
        if (actor.role() != TenantRole.OWNER && actor.role() != TenantRole.ADMIN) {
            throw accessDenied();
        }
        repository.saveMembership(target.suspend(timeProvider.now()));
    }

    @Override
    @Transactional
    public OrganizationView transferOwnership(TransferOrganizationOwnershipCommand command) {
        Tenant organization = requireActiveOrganizationForUpdate(command.organizationId());
        TenantMembership actor = requireActiveMembership(
                organization.id(), command.actorUserId(), true);
        if (actor.role() != TenantRole.OWNER
                || !command.actorUserId().equals(organization.creatorUserId())) {
            throw accessDenied();
        }
        if (command.actorUserId().equals(command.newOwnerUserId())) {
            return toView(organization);
        }
        TenantMembership nextOwner = requireActiveMembership(
                organization.id(), command.newOwnerUserId(), true);
        Instant now = timeProvider.now();
        repository.saveMembership(actor.changeRole(TenantRole.ADMIN, now));
        repository.saveMembership(nextOwner.changeRole(TenantRole.OWNER, now));
        Tenant transferred = organization.assignCreator(nextOwner.userId(), now);
        repository.saveTenant(transferred);
        return toView(transferred);
    }

    @Override
    @Transactional
    public OrganizationLeaveView leaveOrganization(LeaveOrganizationCommand command) {
        Tenant organization = requireActiveOrganizationForUpdate(command.organizationId());
        TenantMembership membership = requireActiveMembership(
                organization.id(), command.userId(), true);
        long activeBefore = repository.countActiveMemberships(organization.id());
        if (membership.role() == TenantRole.OWNER && activeBefore > 1L) {
            throw new BusinessException(
                    "Transfer Organization ownership before leaving",
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_OWNER_TRANSFER_REQUIRED");
        }
        repository.saveMembership(membership.suspend(timeProvider.now()));
        long remaining = repository.countActiveMemberships(organization.id());
        TenantStatus status = organization.status();
        if (remaining == 0L) {
            Tenant deleting = organization.markDeleting(timeProvider.now());
            repository.saveTenant(deleting);
            cleanupApi.enqueue(deleting.id());
            status = deleting.status();
        }
        return new OrganizationLeaveView(organization.id(), status, remaining);
    }

    private Tenant requireVisibleOrganization(String organizationId, String userId) {
        Tenant organization = repository.findTenantById(organizationId)
                .filter(tenant -> tenant.status() != TenantStatus.DELETED)
                .orElseThrow(OrganizationApplicationService::organizationNotFound);
        requireActiveMembership(organization.id(), userId, false);
        return organization;
    }

    private Tenant requireActiveOrganizationForUpdate(String organizationId) {
        Tenant organization = repository.findTenantByIdForUpdate(organizationId)
                .orElseThrow(OrganizationApplicationService::organizationNotFound);
        if (!organization.isActive()) {
            throw organizationNotActive();
        }
        return organization;
    }

    private TenantMembership requireActiveMembership(
            String organizationId,
            String userId,
            boolean forUpdate) {
        TenantMembership membership = (forUpdate
                ? repository.findMembershipForUpdate(organizationId, userId)
                : repository.findMembership(organizationId, userId))
                .filter(TenantMembership::isActive)
                .orElseThrow(OrganizationApplicationService::organizationNotFound);
        return membership;
    }

    private UserIdentity requireUser(String userId) {
        return repository.findUserById(requireText(userId, "userId"))
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private void rejectDuplicateSlug(String slug) {
        if (repository.findTenantBySlug(slug).isPresent()) {
            throw slugConflict();
        }
    }

    private static void requireCanManageRole(TenantRole actor, TenantRole requested) {
        if (actor == TenantRole.OWNER) {
            return;
        }
        if (actor == TenantRole.ADMIN
                && (requested == TenantRole.MEMBER || requested == TenantRole.VIEWER)) {
            return;
        }
        throw accessDenied();
    }

    private static TenantRole requireAssignableRole(TenantRole role) {
        if (role == null) {
            throw invalidInput("Organization role is required");
        }
        if (role == TenantRole.OWNER) {
            throw new BusinessException(
                    "Use ownership transfer to assign OWNER",
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_USE_OWNER_TRANSFER");
        }
        return role;
    }

    private static String normalizeName(String value) {
        String name = requireText(value, "name");
        if (name.length() > 120) {
            throw invalidInput("Organization name must not exceed 120 characters");
        }
        return name;
    }

    private static String normalizeSlug(String value) {
        String slug = requireText(value, "slug").toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw invalidInput("Organization slug is invalid");
        }
        return slug;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidInput(field + " is required");
        }
        return value.trim();
    }

    private static OrganizationView toView(Tenant tenant) {
        return new OrganizationView(
                tenant.id(), tenant.name(), tenant.slug(), tenant.creatorUserId(),
                tenant.status(), tenant.createdAt(), tenant.updatedAt(),
                tenant.deletionRequestedAt());
    }

    private static OrganizationMembershipView toView(TenantMembership membership) {
        return new OrganizationMembershipView(
                membership.tenantId(), membership.userId(), membership.role(),
                membership.status(), membership.joinedAt(), membership.updatedAt());
    }

    private static UserView toView(UserIdentity user) {
        return new UserView(
                user.id(), user.tenantId(), user.externalId(), user.displayName(),
                user.createdAt());
    }

    private static BusinessException organizationNotFound() {
        return new BusinessException(
                "Organization not found", HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND");
    }

    private static BusinessException organizationNotActive() {
        return new BusinessException(
                "Organization is not active",
                HttpStatus.CONFLICT,
                "ORGANIZATION_NOT_ACTIVE");
    }

    private static BusinessException ownerImmutable() {
        return new BusinessException(
                "Organization OWNER can change only through ownership transfer",
                HttpStatus.CONFLICT,
                "ORGANIZATION_OWNER_IMMUTABLE");
    }

    private static BusinessException accessDenied() {
        return new BusinessException(
                "Organization access denied",
                HttpStatus.FORBIDDEN,
                "ORGANIZATION_ACCESS_DENIED");
    }

    private static BusinessException slugConflict() {
        return new BusinessException(
                "Organization slug already exists",
                HttpStatus.CONFLICT,
                "ORGANIZATION_SLUG_CONFLICT");
    }

    private static BusinessException invalidInput(String message) {
        return new BusinessException(
                message, HttpStatus.BAD_REQUEST, "ORGANIZATION_INPUT_INVALID");
    }
}
