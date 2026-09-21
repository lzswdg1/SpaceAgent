package com.spaceagent.platform.inference.api;

/**
 * Public model provider ownership port exposed to other modules.
 */
public interface InferenceOwnershipPort {
    boolean isOwner(String providerId, String principalId);
}
