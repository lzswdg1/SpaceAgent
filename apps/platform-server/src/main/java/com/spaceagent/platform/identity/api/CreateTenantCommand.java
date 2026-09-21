package com.spaceagent.platform.identity.api;

/**
 * Public command for creating a tenant.
 */
public record CreateTenantCommand(String name, String slug) {

    public CreateTenantCommand {
        requireNonBlank(name, "name");
        requireNonBlank(slug, "slug");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
