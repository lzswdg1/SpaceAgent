package com.spaceagent.platform.identity.api;

/**
 * Public command for authenticating a username/password identity.
 */
public record AuthenticateIdentityCommand(String username, String password) {

    public AuthenticateIdentityCommand {
        requireNonBlank(username, "username");
        requireNonBlank(password, "password");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
