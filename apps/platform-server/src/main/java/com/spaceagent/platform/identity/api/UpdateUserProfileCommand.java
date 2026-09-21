package com.spaceagent.platform.identity.api;

/**
 * Public command for updating a user profile. Null fields are left unchanged.
 */
public record UpdateUserProfileCommand(
        String userId,
        String preferredTone,
        String timezone,
        String summary) {

    public UpdateUserProfileCommand {
        requireNonBlank(userId, "userId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
