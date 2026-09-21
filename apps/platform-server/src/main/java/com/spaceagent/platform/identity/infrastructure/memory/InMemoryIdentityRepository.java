package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.Tenant;
import com.spaceagent.platform.identity.domain.TenantMembership;
import com.spaceagent.platform.identity.domain.UserIdentity;
import com.spaceagent.platform.identity.domain.UserProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference in-module identity repository. It intentionally owns no framework
 * persistence API and is replaced by the PostgreSQL adapter in a later M4/M5
 * increment.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdentityRepository implements IdentityRepository {

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, UserIdentity> users = new ConcurrentHashMap<>();
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, TenantMembership> memberships = new ConcurrentHashMap<>();

    @Override
    public Optional<UserIdentity> findUserById(String id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<UserIdentity> findUserByExternalId(String tenantId, String externalId) {
        return users.values().stream()
                .filter(user -> tenantId.equals(user.tenantId()))
                .filter(user -> externalId.equals(user.externalId()))
                .findFirst();
    }

    @Override
    public List<UserIdentity> findUsersByExternalIdIgnoreCase(String externalId) {
        return users.values().stream()
                .filter(user -> externalId.equalsIgnoreCase(user.externalId()))
                .toList();
    }

    @Override
    public List<UserIdentity> findUsersByTenantId(String tenantId) {
        return users.values().stream()
                .filter(user -> tenantId.equals(user.tenantId()))
                .toList();
    }

    @Override
    public Optional<Tenant> findTenantById(String id) {
        return Optional.ofNullable(tenants.get(id));
    }

    @Override
    public List<Tenant> findTenantsByIds(List<String> ids) {
        return ids.stream().distinct().map(tenants::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Optional<Tenant> findTenantByIdForUpdate(String id) {
        return findTenantById(id);
    }

    @Override
    public Optional<Tenant> findTenantBySlug(String slug) {
        return tenants.values().stream()
                .filter(tenant -> slug.equals(tenant.slug()))
                .findFirst();
    }

    @Override
    public Optional<TenantMembership> findMembership(String tenantId, String userId) {
        return Optional.ofNullable(memberships.get(membershipKey(tenantId, userId)));
    }

    @Override
    public Optional<TenantMembership> findMembershipForUpdate(String tenantId, String userId) {
        return findMembership(tenantId, userId);
    }

    @Override
    public List<TenantMembership> findMembershipsByUserId(String userId) {
        return memberships.values().stream()
                .filter(membership -> userId.equals(membership.userId()))
                .toList();
    }

    @Override
    public List<TenantMembership> findMembershipsByTenantId(String tenantId) {
        return memberships.values().stream()
                .filter(membership -> tenantId.equals(membership.tenantId()))
                .toList();
    }

    @Override
    public long countActiveMemberships(String tenantId) {
        return memberships.values().stream()
                .filter(membership -> tenantId.equals(membership.tenantId()))
                .filter(TenantMembership::isActive)
                .count();
    }

    @Override
    public void saveUser(UserIdentity user) {
        users.put(user.id(), user);
    }

    @Override
    public void saveTenant(Tenant tenant) {
        tenants.put(tenant.id(), tenant);
    }

    @Override
    public void saveMembership(TenantMembership membership) {
        memberships.put(membershipKey(membership.tenantId(), membership.userId()), membership);
    }

    @Override
    public void updateUserDisplayName(String userId, String displayName) {
        users.computeIfPresent(userId, (id, current) -> new UserIdentity(
                current.id(),
                current.tenantId(),
                current.externalId(),
                displayName,
                current.createdAt(),
                current.updatedAt()
        ));
    }

    @Override
    public Optional<UserProfile> findProfileByUserId(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void saveProfile(UserProfile profile) {
        profiles.put(profile.userId(), profile);
    }

    private String membershipKey(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }
}
