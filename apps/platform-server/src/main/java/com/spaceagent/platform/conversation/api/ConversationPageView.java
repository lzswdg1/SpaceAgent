package com.spaceagent.platform.conversation.api;

import java.util.List;

public record ConversationPageView(List<ConversationView> items, long total) {
    public ConversationPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
