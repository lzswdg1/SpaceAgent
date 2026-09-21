package com.spaceagent.platform.conversation.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

public interface ConversationSystemAdministrationApi {
    ConversationOverview overview();
    SystemAdministrationPage<UserResourceSummary> conversationsByOwner(
            String userId, int page, int pageSize);
    ConversationDeletionEvidence deletionEvidence(String userId);
    record ConversationOverview(long conversations, long activeConversations, long messages) {
    }
    record ConversationDeletionEvidence(long conversations, long messages) {
    }
}
