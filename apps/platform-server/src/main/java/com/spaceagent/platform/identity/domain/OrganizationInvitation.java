package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/** Identity-owned invitation. Plaintext invitation tokens are never stored here. */
public record OrganizationInvitation(
        String id,
        String organizationId,
        String email,
        TenantRole role,
        OrganizationInvitationStatus status,
        String tokenHash,
        String invitedByUserId,
        Instant expiresAt,
        String acceptedByUserId,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt,
        Instant revokedAt) {

    public OrganizationInvitationStatus effectiveStatus(Instant now) {
        return status == OrganizationInvitationStatus.PENDING && !now.isBefore(expiresAt)
                ? OrganizationInvitationStatus.EXPIRED
                : status;
    }

    public boolean isPendingAt(Instant now) {
        return effectiveStatus(now) == OrganizationInvitationStatus.PENDING;
    }

    public OrganizationInvitation accept(String userId, Instant now) {
        if (!isPendingAt(now)) {
            throw new IllegalStateException("Only a pending invitation can be accepted");
        }
        return new OrganizationInvitation(
                id, organizationId, email, role, OrganizationInvitationStatus.ACCEPTED,
                tokenHash, invitedByUserId, expiresAt, userId, createdAt, now, now, null);
    }

    public OrganizationInvitation revoke(Instant now) {
        if (!isPendingAt(now)) {
            throw new IllegalStateException("Only a pending invitation can be revoked");
        }
        return new OrganizationInvitation(
                id, organizationId, email, role, OrganizationInvitationStatus.REVOKED,
                tokenHash, invitedByUserId, expiresAt, null, createdAt, now, null, now);
    }

    public OrganizationInvitation expire(Instant now) {
        if (status != OrganizationInvitationStatus.PENDING || now.isBefore(expiresAt)) {
            return this;
        }
        return new OrganizationInvitation(
                id, organizationId, email, role, OrganizationInvitationStatus.EXPIRED,
                tokenHash, invitedByUserId, expiresAt, null, createdAt, now, null, null);
    }
}
