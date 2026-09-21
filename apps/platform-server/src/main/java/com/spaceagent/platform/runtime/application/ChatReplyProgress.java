package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi.ChatStreamObserver;
import com.spaceagent.platform.runtime.api.ChatRuntimeEvent;

/** A bounded-rate durable draft; only visible answer text, never reasoning or Tool preambles. */
final class ChatReplyProgress implements ChatStreamObserver {
    private final ConversationApplicationApi conversations;
    private final String tenant,user,conversation,reservation;
    private final ChatStreamObserver downstream;
    private final StringBuilder content=new StringBuilder();
    private int savedLength;
    private long savedAt=System.nanoTime();
    ChatReplyProgress(ConversationApplicationApi conversations,String tenant,String user,String conversation,
            String reservation,ChatStreamObserver downstream) {
        this.conversations=conversations;this.tenant=tenant;this.user=user;this.conversation=conversation;
        this.reservation=reservation;this.downstream=downstream;
    }
    public void onRuntimeEvent(ChatRuntimeEvent event){if(downstream!=null)downstream.onRuntimeEvent(event);}
    public void onReasoningDelta(String text){if(downstream!=null)downstream.onReasoningDelta(text);}
    public void onContentDelta(String text){
        content.append(text);
        if(content.length()-savedLength>=2048 || System.nanoTime()-savedAt>=1_000_000_000L)flush();
        if(downstream!=null)downstream.onContentDelta(text);
    }
    void flush(){
        if(content.length()==savedLength)return;
        conversations.updateReplyDraft(tenant,user,conversation,reservation,content.toString());
        savedLength=content.length();savedAt=System.nanoTime();
    }
}
