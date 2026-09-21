package com.spaceagent.platform.identity.domain;

/** Durable Organization invitation lifecycle. */
public enum OrganizationInvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED
}
