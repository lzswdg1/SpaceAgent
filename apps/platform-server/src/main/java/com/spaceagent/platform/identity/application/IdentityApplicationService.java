package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.api.TenantView;
import com.spaceagent.platform.identity.api.UpdateUserProfileCommand;
import com.spaceagent.platform.identity.api.UserProfileView;
import com.spaceagent.platform.identity.api.UserView;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserIdentity;
import com.spaceagent.platform.identity.domain.UserProfile;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-module application coordinator for identity. It is the only implementation
 * other modules should use directly; they depend on {@link IdentityApplicationApi}
 * or {@link IdentityOwnershipPort}, never on the repository implementation.
 */
@Service
public class IdentityApplicationService implements IdentityApplicationApi, IdentityOwnershipPort {

    private final IdentityRepository repository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public IdentityApplicationService(
            IdentityRepository repository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public TenantView createTenant(CreateTenantCommand command) {
        Instant now = timeProvider.now();
        Tenant tenant = new Tenant(
                idGenerator.nextId(),
                command.name(),
                command.slug(),
                TenantStatus.ACTIVE,
                now,
                now);
        repository.saveTenant(tenant);
        return toView(tenant);
    }

    @Override
    public Optional<TenantView> findTenant(String tenantId) {
        return repository.findTenantById(tenantId).map(IdentityApplicationService::toView);
    }

    @Override
    public UserView createUser(CreateUserCommand command) {
        if (repository.findTenantById(command.tenantId()).filter(Tenant::isActive).isEmpty()) {
            throw new BusinessException("Tenant not found: " + command.tenantId(), HttpStatus.NOT_FOUND);
        }

        Instant now = timeProvider.now();
        UserIdentity user = new UserIdentity(
                idGenerator.nextId(),
                command.tenantId(),
                command.externalId(),
                command.displayName(),
                now,
                now);
        repository.saveUser(user);
        return toView(user);
    }

    @Override
    public Optional<UserView> findUser(String userId) {
        return repository.findUserById(userId).map(IdentityApplicationService::toView);
    }

    @Override
    public boolean isExternalIdentityReserved(String externalId) {
        return externalId != null
                && !repository.findUsersByExternalIdIgnoreCase(externalId.trim()).isEmpty();
    }

    @Override
    public List<UserView> findUsersByTenant(String tenantId) {
        return repository.findUsersByTenantId(tenantId).stream()
                .map(IdentityApplicationService::toView)
                .toList();
    }

    @Override
    public TenantMembershipView addTenantMembership(AddTenantMembershipCommand command) {
        if (repository.findTenantById(command.tenantId()).filter(Tenant::isActive).isEmpty()) {
            throw new BusinessException("Tenant not found: " + command.tenantId(), HttpStatus.NOT_FOUND);
        }
        if (repository.findUserById(command.userId()).isEmpty()) {
            throw new BusinessException("User not found: " + command.userId(), HttpStatus.NOT_FOUND);
        }
        Instant now = timeProvider.now();
        TenantMembership membership = new TenantMembership(
                command.tenantId(),
                command.userId(),
                command.role(),
                TenantMembershipStatus.ACTIVE,
                now,
                now);
        repository.saveMembership(membership);
        return toView(membership);
    }

    @Override
    public Optional<TenantMembershipView> findTenantMembership(String tenantId, String userId) {
        return repository.findMembership(tenantId, userId)
                .map(IdentityApplicationService::toView);
    }

    @Override
    public UserView updateDisplayName(String userId, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (repository.findUserById(userId).isEmpty()) {
            throw new BusinessException("User not found: " + userId, HttpStatus.NOT_FOUND);
        }
        repository.updateUserDisplayName(userId, displayName.trim());
        return repository.findUserById(userId)
                .map(IdentityApplicationService::toView)
                .orElseThrow();
    }

    @Override
    public Optional<UserProfileView> findProfile(String userId) {
        return repository.findProfileByUserId(userId).map(IdentityApplicationService::toProfileView);
    }

    @Override
    public UserProfileView saveProfile(UpdateUserProfileCommand command) {
        if (repository.findUserById(command.userId()).isEmpty()) {
            throw new BusinessException("User not found: " + command.userId(), HttpStatus.NOT_FOUND);
        }
        Instant now = timeProvider.now();
        UserProfile current = repository.findProfileByUserId(command.userId())
                .orElse(UserProfile.defaultProfile(command.userId(), now));
        UserProfile updated = new UserProfile(
                command.userId(),
                command.preferredTone() == null ? current.preferredTone() : command.preferredTone(),
                command.timezone() == null ? current.timezone() : command.timezone(),
                command.summary() == null ? current.summary() : command.summary(),
                current.createdAt(),
                now
        );
        repository.saveProfile(updated);
        return toProfileView(updated);
    }

    @Override
    public boolean isMemberOfTenant(String tenantId, String principalId) {
        return repository.findTenantById(tenantId)
                .filter(Tenant::isActive)
                .flatMap(tenant -> repository.findMembership(tenant.id(), principalId))
                .filter(TenantMembership::isActive)
                .isPresent();
    }

    private static TenantView toView(Tenant tenant) {
        return new TenantView(
                tenant.id(),
                tenant.name(),
                tenant.slug(),
                tenant.status(),
                tenant.createdAt());
    }

    private static UserView toView(UserIdentity user) {
        return new UserView(
                user.id(),
                user.tenantId(),
                user.externalId(),
                user.displayName(),
                user.createdAt());
    }

    private static TenantMembershipView toView(TenantMembership membership) {
        return new TenantMembershipView(
                membership.tenantId(),
                membership.userId(),
                membership.role(),
                membership.status(),
                membership.joinedAt(),
                membership.updatedAt());
    }

    private static UserProfileView toProfileView(UserProfile profile) {
        return new UserProfileView(
                profile.userId(),
                profile.preferredTone(),
                profile.timezone(),
                profile.summary(),
                profile.createdAt(),
                profile.updatedAt());
    }
}
