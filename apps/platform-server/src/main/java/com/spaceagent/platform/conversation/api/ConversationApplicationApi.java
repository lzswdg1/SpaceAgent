package com.spaceagent.platform.conversation.api;

import java.util.List;
import java.util.Optional;

/**
 * Public conversation application API. Conversation owns message and snapshot state;
 * runtime and chat orchestrators consume this API rather than conversation persistence.
 */
public interface ConversationApplicationApi {

    ConversationView start(StartConversationCommand command);

    Optional<ConversationView> find(String conversationId);

    ConversationPageView pageByTenantAndUser(
            String tenantId, String userId, int page, int size);

    default ConversationPageView pageByScope(String tenantId,String userId,int page,int size,String scope,String query) {
        return pageByTenantAndUser(tenantId,userId,page,size);
    }
    default MessagePageView messagePage(String tenantId,String userId,String conversationId,Integer beforeSequence,int size) {
        throw new UnsupportedOperationException("Message paging unavailable");
    }
    record MessagePageView(List<MessageView> items,Integer beforeSequence,boolean hasMore) { }

    ConversationPageView pageByProjectDirectory(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, int page, int size);

    ConversationView bindAgent(BindConversationAgentCommand command);

    ConversationView switchAgent(SwitchConversationAgentCommand command);

    ConversationView rename(RenameConversationCommand command);

    ConversationView setActiveTask(SetConversationActiveTaskCommand command);

    MessageView append(AppendMessageCommand command);

    ReservedReplyView reserveReply(String tenantId, String userId, String conversationId, String userContent);

    MessageView completeReply(String tenantId, String userId, String conversationId,
                              String reservationId, String assistantContent);
    default void updateReplyDraft(String tenantId,String userId,String conversationId,String reservationId,String content) { }
    default Optional<MessageView> findReply(String tenantId,String userId,String conversationId,String reservationId){return Optional.empty();}

    List<MessageView> messages(String conversationId);

    List<MessageView> recentMessages(String conversationId, int limit);

    long messageCount(String conversationId);

    void close(CloseConversationCommand command);

    void delete(DeleteConversationCommand command);
}
