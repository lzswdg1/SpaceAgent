package com.spaceagent.platform.identity.api;

/**
 * Public command for creating a tenant-scoped user identity.
 */
public record CreateUserCommand(
        String tenantId,
        String externalId,
        String displayName) {

    public CreateUserCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(externalId, "externalId");
        requireNonBlank(displayName, "displayName");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
