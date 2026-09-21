package com.spaceagent.platform.identity.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for identity persistence.
 */
public interface IdentityRepository {
    Optional<UserIdentity> findUserById(String id);

    Optional<UserIdentity> findUserByExternalId(String tenantId, String externalId);

    List<UserIdentity> findUsersByExternalIdIgnoreCase(String externalId);

    List<UserIdentity> findUsersByTenantId(String tenantId);

    Optional<Tenant> findTenantById(String id);

    List<Tenant> findTenantsByIds(List<String> ids);

    Optional<Tenant> findTenantByIdForUpdate(String id);

    Optional<Tenant> findTenantBySlug(String slug);

    Optional<TenantMembership> findMembership(String tenantId, String userId);

    Optional<TenantMembership> findMembershipForUpdate(String tenantId, String userId);

    List<TenantMembership> findMembershipsByUserId(String userId);

    List<TenantMembership> findMembershipsByTenantId(String tenantId);

    long countActiveMemberships(String tenantId);

    void saveUser(UserIdentity user);

    void saveTenant(Tenant tenant);

    void saveMembership(TenantMembership membership);

    void updateUserDisplayName(String userId, String displayName);

    Optional<UserProfile> findProfileByUserId(String userId);

    void saveProfile(UserProfile profile);
}
