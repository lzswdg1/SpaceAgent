package com.spaceagent.platform.conversation.api;

/** Durable pair reservation preventing concurrent Chat runs from reusing message sequences. */
public record ReservedReplyView(
        MessageView userMessage,
        String assistantReservationId,
        int assistantSequence) {
}
