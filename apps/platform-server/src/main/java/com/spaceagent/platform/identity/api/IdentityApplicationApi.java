package com.spaceagent.platform.identity.api;

import java.util.List;
import java.util.Optional;

/**
 * Public identity application API used by other platform-server modules.
 */
public interface IdentityApplicationApi {

    TenantView createTenant(CreateTenantCommand command);

    Optional<TenantView> findTenant(String tenantId);

    UserView createUser(CreateUserCommand command);

    Optional<UserView> findUser(String userId);

    boolean isExternalIdentityReserved(String externalId);

    List<UserView> findUsersByTenant(String tenantId);

    TenantMembershipView addTenantMembership(AddTenantMembershipCommand command);

    Optional<TenantMembershipView> findTenantMembership(String tenantId, String userId);

    UserView updateDisplayName(String userId, String displayName);

    Optional<UserProfileView> findProfile(String userId);

    UserProfileView saveProfile(UpdateUserProfileCommand command);
}
