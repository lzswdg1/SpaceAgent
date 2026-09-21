package com.spaceagent.platform.conversation.api;

public interface ConversationCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);
}
