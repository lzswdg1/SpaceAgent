package com.spaceagent.platform.conversation.application;

import com.spaceagent.platform.conversation.api.ConversationContextSnapshotApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotView;
import com.spaceagent.platform.conversation.api.SaveConversationContextSnapshotCommand;
import com.spaceagent.platform.conversation.domain.ConversationContextSnapshot;
import com.spaceagent.platform.conversation.domain.ConversationContextSnapshotRepository;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable conversation context snapshot coordinator. It owns snapshot creation and
 * lookup but does not orchestrate agent runs.
 */
@Service
public class ConversationContextSnapshotService
        implements ConversationContextSnapshotApplicationApi {

    private final ConversationContextSnapshotRepository repository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public ConversationContextSnapshotService(
            ConversationContextSnapshotRepository repository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public ConversationContextSnapshotView save(SaveConversationContextSnapshotCommand command) {
        Instant now = timeProvider.now();
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                idGenerator.nextId(),
                command.conversationId(),
                command.version(),
                command.summary(),
                command.fromMessageSequence(),
                command.toMessageSequence(),
                command.tokenCount(),
                command.checksum(),
                now);
        ConversationContextSnapshot persisted = repository.save(snapshot);
        if (!sameContent(snapshot, persisted)) {
            throw new IllegalStateException(
                    "Conversation context snapshot version already has different content");
        }
        return toView(persisted);
    }

    @Override
    public Optional<ConversationContextSnapshotView> findById(String snapshotId) {
        return repository.findById(snapshotId)
                .map(ConversationContextSnapshotService::toView);
    }

    @Override
    public Optional<ConversationContextSnapshotView> findLatestByConversationId(
            String conversationId) {
        return repository.findLatestByConversationId(conversationId)
                .map(ConversationContextSnapshotService::toView);
    }

    private static ConversationContextSnapshotView toView(ConversationContextSnapshot snapshot) {
        return new ConversationContextSnapshotView(
                snapshot.id(),
                snapshot.conversationId(),
                snapshot.version(),
                snapshot.summary(),
                snapshot.fromMessageSequence(),
                snapshot.toMessageSequence(),
                snapshot.tokenCount(),
                snapshot.checksum(),
                snapshot.createdAt());
    }

    private static boolean sameContent(
            ConversationContextSnapshot left, ConversationContextSnapshot right) {
        return left.conversationId().equals(right.conversationId())
                && left.version() == right.version()
                && left.summary().equals(right.summary())
                && left.fromMessageSequence() == right.fromMessageSequence()
                && left.toMessageSequence() == right.toMessageSequence()
                && left.tokenCount() == right.tokenCount()
                && left.checksum().equals(right.checksum());
    }
}
