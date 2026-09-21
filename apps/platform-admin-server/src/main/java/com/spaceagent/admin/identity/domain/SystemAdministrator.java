package com.spaceagent.admin.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SystemAdministrator(
        UUID id,
        String loginName,
        String displayName,
        AdminPrincipalStatus status,
        AdminRole role,
        long credentialVersion,
        boolean mfaRequired,
        boolean mustChangePassword,
        Instant lastSuccessfulLoginAt,
        Instant createdAt,
        Instant updatedAt) {

    public SystemAdministrator {
        Objects.requireNonNull(id, "id");
        loginName = requireText(loginName, "loginName");
        displayName = requireText(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(role, "role");
        if (credentialVersion < 1) throw new IllegalArgumentException("credentialVersion must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean canAuthenticate() {
        return status == AdminPrincipalStatus.ACTIVE;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
