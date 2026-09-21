package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.OrganizationInvitation;
import com.spaceagent.platform.identity.domain.OrganizationInvitationRepository;
import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryOrganizationInvitationRepository implements OrganizationInvitationRepository {

    private final Map<String, OrganizationInvitation> invitations = new ConcurrentHashMap<>();

    @Override
    public Optional<OrganizationInvitation> findByTokenHash(String tokenHash) {
        return invitations.values().stream()
                .filter(invitation -> tokenHash.equals(invitation.tokenHash()))
                .findFirst();
    }

    @Override
    public Optional<OrganizationInvitation> findByTokenHashForUpdate(String tokenHash) {
        return findByTokenHash(tokenHash);
    }

    @Override
    public Optional<OrganizationInvitation> findByIdForUpdate(
            String organizationId,
            String invitationId) {
        return Optional.ofNullable(invitations.get(invitationId))
                .filter(invitation -> organizationId.equals(invitation.organizationId()));
    }

    @Override
    public Optional<OrganizationInvitation> findPendingByOrganizationAndEmail(
            String organizationId,
            String email) {
        return invitations.values().stream()
                .filter(invitation -> organizationId.equals(invitation.organizationId()))
                .filter(invitation -> email.equals(invitation.email()))
                .filter(invitation -> invitation.status() == OrganizationInvitationStatus.PENDING)
                .findFirst();
    }

    @Override
    public List<OrganizationInvitation> findByOrganization(String organizationId) {
        return invitations.values().stream()
                .filter(invitation -> organizationId.equals(invitation.organizationId()))
                .sorted(Comparator.comparing(OrganizationInvitation::createdAt).reversed()
                        .thenComparing(OrganizationInvitation::id))
                .toList();
    }

    @Override
    public synchronized boolean markAcceptedIfPending(
            String tokenHash,
            String userId,
            java.time.Instant now) {
        OrganizationInvitation current = findByTokenHash(tokenHash).orElse(null);
        if (current == null || !current.isPendingAt(now)) {
            return false;
        }
        invitations.put(current.id(), current.accept(userId, now));
        return true;
    }

    @Override
    public void save(OrganizationInvitation invitation) {
        invitations.put(invitation.id(), invitation);
    }
}
