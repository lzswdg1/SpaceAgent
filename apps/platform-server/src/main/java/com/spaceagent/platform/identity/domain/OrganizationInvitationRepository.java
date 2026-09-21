package com.spaceagent.platform.identity.domain;

import java.util.List;
import java.util.Optional;

/** Persistence boundary owned by the Identity module. */
public interface OrganizationInvitationRepository {

    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);

    Optional<OrganizationInvitation> findByTokenHashForUpdate(String tokenHash);

    Optional<OrganizationInvitation> findByIdForUpdate(String organizationId, String invitationId);

    Optional<OrganizationInvitation> findPendingByOrganizationAndEmail(
            String organizationId,
            String email);

    List<OrganizationInvitation> findByOrganization(String organizationId);

    boolean markAcceptedIfPending(String tokenHash, String userId, java.time.Instant now);

    void save(OrganizationInvitation invitation);
}
