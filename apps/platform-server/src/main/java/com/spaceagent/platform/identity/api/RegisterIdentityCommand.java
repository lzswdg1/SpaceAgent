package com.spaceagent.platform.identity.api;

/**
 * Public command for registering a username/password identity.
 */
public record RegisterIdentityCommand(
        String username,
        String password,
        String displayName) {

    public RegisterIdentityCommand {
        requireNonBlank(username, "username");
        requireNonBlank(password, "password");
        if (password.length() < 10 || password.length() > 64) {
            throw new IllegalArgumentException("password must be between 10 and 64 characters");
        }
        displayName = displayName == null || displayName.isBlank() ? username.trim() : displayName.trim();
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
