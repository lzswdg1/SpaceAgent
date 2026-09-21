package com.spaceagent.platform.conversation;

import com.spaceagent.platform.conversation.api.AppendMessageCommand;
import com.spaceagent.platform.conversation.api.CloseConversationCommand;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.api.SwitchConversationAgentCommand;
import com.spaceagent.platform.conversation.api.RenameConversationCommand;
import com.spaceagent.platform.conversation.application.ConversationApplicationService;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryConversationRepository;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryMessageRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformConversationApplicationServiceTest {

    private ConversationApplicationApi conversationApi;
    private AtomicInteger ids;

    @BeforeEach
    void setUp() {
        ids = new AtomicInteger();
        conversationApi = new ConversationApplicationService(
                new InMemoryConversationRepository(),
                new InMemoryMessageRepository(),
                () -> "id-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-08-18T00:00:00Z"));
    }

    @Test
    void conversationOwnsMessagesAndSupportsHistoryQueries() {
        ConversationView conversation = conversationApi.start(new StartConversationCommand(
                null, null, "tenant-1", "user-1", "agent-1", "Hello"));

        conversationApi.append(new AppendMessageCommand(conversation.id(), 0, "USER", "hi"));
        conversationApi.append(new AppendMessageCommand(conversation.id(), 1, "ASSISTANT", "hello"));

        assertEquals(2, conversationApi.messageCount(conversation.id()));
        assertEquals(2, conversationApi.messages(conversation.id()).size());
        assertEquals("user-1", conversationApi.find(conversation.id()).orElseThrow().userId());
        assertEquals(ConversationStatus.ACTIVE, conversationApi.find(conversation.id()).orElseThrow().status());
    }

    @Test
    void renameChangesOnlyTheOwnedActiveConversationTitle() {
        var original=conversationApi.start(new StartConversationCommand(null,null,"tenant-1","user-1","agent-1","Before"));
        var reply=conversationApi.reserveReply("tenant-1","user-1",original.id(),"Question");
        var renamed=conversationApi.rename(new RenameConversationCommand("tenant-1","user-1",original.id(),"  新的名称  "));
        assertEquals("新的名称",renamed.title());
        assertEquals(original.agentId(),renamed.agentId());
        assertEquals(original.createdAt(),renamed.createdAt());
        conversationApi.completeReply("tenant-1","user-1",original.id(),reply.assistantReservationId(),"Answer");
        assertEquals("新的名称",conversationApi.find(original.id()).orElseThrow().title());
        assertEquals(2,conversationApi.messages(original.id()).size());
        assertThrows(BusinessException.class,()->conversationApi.rename(new RenameConversationCommand("tenant-2","user-1",original.id(),"bad")));
        assertThrows(BusinessException.class,()->conversationApi.rename(new RenameConversationCommand("tenant-1","user-2",original.id(),"bad")));
        for(String name:List.of(" ","x".repeat(201)))assertThrows(BusinessException.class,()->conversationApi.rename(new RenameConversationCommand("tenant-1","user-1",original.id(),name)));
        conversationApi.close(new CloseConversationCommand(original.id()));
        assertThrows(BusinessException.class,()->conversationApi.rename(new RenameConversationCommand("tenant-1","user-1",original.id(),"closed")));
    }

    @Test void scopeSearchAndHistoryCursorReachOlderRecordsWithoutCrossingOwnership() {
        for(int i=0;i<105;i++)conversationApi.start(new StartConversationCommand(null,null,"tenant-1","user-1","agent-1","topic-"+i));
        assertEquals(105,conversationApi.pageByScope("tenant-1","user-1",1,100,"CHAT","").total());
        assertEquals(5,conversationApi.pageByScope("tenant-1","user-1",2,100,"CHAT","").items().size());
        assertEquals(1,conversationApi.pageByScope("tenant-1","user-1",1,100,"CHAT","topic-104").total());
        assertEquals(0,conversationApi.pageByScope("tenant-2","user-1",1,100,"CHAT","").total());
        var conversation=conversationApi.start(new StartConversationCommand(null,null,"tenant-1","user-1","agent-1","history"));
        for(int i=0;i<205;i++)conversationApi.append(new AppendMessageCommand(conversation.id(),i,"USER","message-"+i));
        var newest=conversationApi.messagePage("tenant-1","user-1",conversation.id(),null,200);
        assertEquals(200,newest.items().size());assertEquals(true,newest.hasMore());
        var oldest=conversationApi.messagePage("tenant-1","user-1",conversation.id(),newest.beforeSequence(),200);
        assertEquals(5,oldest.items().size());assertEquals("message-0",oldest.items().getFirst().content());assertEquals(false,oldest.hasMore());
        assertThrows(BusinessException.class,()->conversationApi.messagePage("tenant-2","user-1",conversation.id(),null,50));
    }

    @Test
    void ownerCanSwitchTheAgentUsedByFutureConversationRuns() {
        ConversationView conversation = conversationApi.start(new StartConversationCommand(
                null, null, "tenant-1", "user-1", "agent-1", "Switch Agent"));

        ConversationView updated = conversationApi.switchAgent(new SwitchConversationAgentCommand(
                "tenant-1", "user-1", conversation.id(), "agent-2"));

        assertEquals("agent-2", updated.agentId());
        assertEquals("agent-2", conversationApi.find(conversation.id()).orElseThrow().agentId());
        assertThrows(BusinessException.class, () -> conversationApi.switchAgent(
                new SwitchConversationAgentCommand(
                        "tenant-1", "other-user", conversation.id(), "agent-1")));
        conversationApi.close(new CloseConversationCommand(conversation.id()));
        assertThrows(BusinessException.class, () -> conversationApi.switchAgent(
                new SwitchConversationAgentCommand(
                        "tenant-1", "user-1", conversation.id(), "agent-1")));
    }

    @Test
    void concurrentRunsReserveDistinctUserAndAssistantSequences() throws Exception {
        ConversationView conversation = conversationApi.start(new StartConversationCommand(
                null, null, "tenant-1", "user-1", "agent-1", "Concurrent"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 20)
                    .mapToObj(index -> executor.submit(() -> conversationApi.reserveReply(
                            "tenant-1", "user-1", conversation.id(), "message-" + index)))
                    .toList();
            var reservations = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }).toList();
            assertEquals(20, reservations.stream()
                    .map(value -> value.userMessage().sequence()).distinct().count());
            assertEquals(20, reservations.stream()
                    .map(value -> value.assistantSequence()).distinct().count());
            assertEquals(40, reservations.stream()
                    .flatMap(value -> java.util.stream.Stream.of(
                            value.userMessage().sequence(), value.assistantSequence()))
                    .distinct().count());
        }
    }

    @Test
    void conversationAndMessageQueriesAreDatabaseBounded() {
        for (int index = 0; index < 5; index++) {
            conversationApi.start(new StartConversationCommand(
                    null, null, "tenant-1", "user-1", "agent-1", "Conversation " + index));
        }
        var page = conversationApi.pageByTenantAndUser("tenant-1", "user-1", 2, 2);
        assertEquals(5, page.total());
        assertEquals(2, page.items().size());

        ConversationView conversation = page.items().getFirst();
        for (int sequence = 0; sequence < 250; sequence++) {
            conversationApi.append(new AppendMessageCommand(
                    conversation.id(), sequence, "USER", "message-" + sequence));
        }
        assertEquals(200, conversationApi.messages(conversation.id()).size());
        assertEquals("message-50", conversationApi.messages(conversation.id()).getFirst().content());
        assertEquals(10, conversationApi.recentMessages(conversation.id(), 10).size());
    }

    @Test
    void completingTheSameReservedReplyWithIdenticalContentIsIdempotent() {
        ConversationView conversation = conversationApi.start(new StartConversationCommand(
                null, null, "tenant-1", "user-1", "agent-1", "Resume"));
        var reserved = conversationApi.reserveReply(
                "tenant-1", "user-1", conversation.id(), "approve this");

        var first = conversationApi.completeReply(
                "tenant-1", "user-1", conversation.id(),
                reserved.assistantReservationId(), "completed once");
        var replay = conversationApi.completeReply(
                "tenant-1", "user-1", conversation.id(),
                reserved.assistantReservationId(), "completed once");

        assertEquals(first.id(), replay.id());
        assertEquals("ASSISTANT", replay.role());
        assertThrows(BusinessException.class, () -> conversationApi.completeReply(
                "tenant-1", "user-1", conversation.id(),
                reserved.assistantReservationId(), "different content"));
    }
}
