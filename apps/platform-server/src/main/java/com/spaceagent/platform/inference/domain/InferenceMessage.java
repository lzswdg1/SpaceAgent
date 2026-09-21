package com.spaceagent.platform.inference.domain;

/**
 * Model-independent inference message.
 */
public record InferenceMessage(String role, String content) {

    public InferenceMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content == null) {
            content = "";
        }
    }
}
