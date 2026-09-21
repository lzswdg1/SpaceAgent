package com.spaceagent.platform.conversation.application;

import com.spaceagent.platform.conversation.api.AppendMessageCommand;
import com.spaceagent.platform.conversation.api.BindConversationAgentCommand;
import com.spaceagent.platform.conversation.api.CloseConversationCommand;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationOwnershipPort;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.api.ConversationPageView;
import com.spaceagent.platform.conversation.api.DeleteConversationCommand;
import com.spaceagent.platform.conversation.api.MessageView;
import com.spaceagent.platform.conversation.api.ReservedReplyView;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.api.SwitchConversationAgentCommand;
import com.spaceagent.platform.conversation.api.RenameConversationCommand;
import com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand;
import com.spaceagent.platform.conversation.domain.Conversation;
import com.spaceagent.platform.conversation.domain.ConversationRepository;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.conversation.domain.Message;
import com.spaceagent.platform.conversation.domain.MessageRepository;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable conversation coordinator. Conversation owns message and snapshot state; it
 * does not orchestrate agent runs.
 */
@Service
public class ConversationApplicationService implements ConversationApplicationApi, ConversationOwnershipPort {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final TaskApplicationApi taskApi;
    private final ProjectDirectoryApplicationApi directoryApi;

    public ConversationApplicationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this(conversationRepository, messageRepository, idGenerator, timeProvider, null, null);
    }

    public ConversationApplicationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            TaskApplicationApi taskApi) {
        this(conversationRepository, messageRepository, idGenerator, timeProvider, taskApi, null);
    }

    @Autowired
    public ConversationApplicationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            TaskApplicationApi taskApi,
            ProjectDirectoryApplicationApi directoryApi) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.taskApi = taskApi;
        this.directoryApi = directoryApi;
    }

    @Override
    public ConversationView start(StartConversationCommand command) {
        if (command.requestedId() != null) {
            var existing = conversationRepository.findById(command.requestedId()).orElse(null);
            if (existing != null) {
                if (!java.util.Objects.equals(existing.tenantId(), command.tenantId())
                        || !existing.userId().equals(command.userId())
                        || !java.util.Objects.equals(existing.agentId(), command.agentId())) {
                    throw new com.spaceagent.shared.exception.BusinessException(
                            "Stable Conversation ID is already bound", org.springframework.http.HttpStatus.CONFLICT,
                            "AUTOMATION_CONVERSATION_ID_CONFLICT");
                }
                return toView(existing);
            }
        }
        String projectDirectoryId = resolveDirectory(command);
        validateActiveTask(
                command.tenantId(), command.userId(), command.projectId(), null,
                command.activeTaskId());
        Instant now = timeProvider.now();
        Conversation conversation = new Conversation(
                command.requestedId() == null ? idGenerator.nextId() : command.requestedId(),
                command.projectId(),
                projectDirectoryId,
                command.taskId(),
                command.activeTaskId(),
                command.tenantId(),
                command.userId(),
                command.agentId(),
                command.title(),
                ConversationStatus.ACTIVE,
                now,
                now);
        conversationRepository.save(conversation);
        return toView(conversation);
    }

    @Override
    public Optional<ConversationView> find(String conversationId) {
        return conversationRepository.findById(conversationId)
                .map(ConversationApplicationService::toView);
    }

    @Override
    public ConversationPageView pageByTenantAndUser(
            String tenantId, String userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        List<ConversationView> items = conversationRepository.findByTenantAndUserId(
                        tenantId, userId, safeSize, (safePage - 1) * safeSize).stream()
                .map(ConversationApplicationService::toView).toList();
        return new ConversationPageView(
                items, conversationRepository.countByTenantAndUserId(tenantId, userId));
    }

    @Override
    public ConversationPageView pageByProjectDirectory(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, int page, int size) {
        if (directoryApi == null) {
            throw new BusinessException(
                    "ProjectDirectory validation is unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "PROJECT_DIRECTORY_VALIDATION_UNAVAILABLE");
        }
        directoryApi.get(new ProjectDirectoryApplicationApi.Query(
                tenantId, userId, projectId, projectDirectoryId));
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        List<ConversationView> items = conversationRepository.findByProjectDirectoryId(
                        tenantId, userId, projectDirectoryId, safeSize,
                        (safePage - 1) * safeSize).stream()
                .map(ConversationApplicationService::toView).toList();
        return new ConversationPageView(items,
                conversationRepository.countByProjectDirectoryId(
                        tenantId, userId, projectDirectoryId));
    }

    @Override public ConversationPageView pageByScope(String tenantId,String userId,int page,int size,String scope,String query) {
        if(!java.util.Set.of("ALL","CHAT","PROJECT").contains(scope)) throw new BusinessException("Invalid conversation scope",HttpStatus.BAD_REQUEST);
        String filter=query==null?"":query.trim();
        if(filter.length()>200)throw new BusinessException("Search is too long",HttpStatus.BAD_REQUEST);
        int safeSize=Math.max(1,Math.min(200,size));
        int offset=(int)Math.min(Integer.MAX_VALUE,((long)Math.max(1,page)-1)*safeSize);
        return new ConversationPageView(conversationRepository.findFiltered(tenantId,userId,scope,filter,safeSize,offset)
                .stream().map(ConversationApplicationService::toView).toList(),
                conversationRepository.countFiltered(tenantId,userId,scope,filter));
    }

    @Override public MessagePageView messagePage(String tenantId,String userId,String conversationId,Integer beforeSequence,int size) {
        Conversation conversation=requireConversation(conversationId);
        if(!tenantId.equals(conversation.tenantId()) || !userId.equals(conversation.userId()))
            throw new BusinessException("Conversation not found",HttpStatus.NOT_FOUND);
        int limit=Math.max(1,Math.min(200,size));
        var descending=messageRepository.findBeforeSequence(conversationId,beforeSequence==null?Integer.MAX_VALUE:Math.max(0,beforeSequence),limit+1);
        var items=descending.stream().limit(limit).sorted(java.util.Comparator.comparingInt(com.spaceagent.platform.conversation.domain.Message::sequence))
                .map(ConversationApplicationService::toView).toList();
        return new MessagePageView(items,items.isEmpty()?null:items.getFirst().sequence(),descending.size()>limit);
    }

    @Override
    @Transactional
    public ConversationView bindAgent(BindConversationAgentCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        if (conversation.agentId() != null && !conversation.agentId().equals(command.agentId())) {
            throw new BusinessException(
                    "Conversation is already bound to another agent",
                    HttpStatus.CONFLICT);
        }
        Conversation updated = conversation.bindAgent(command.agentId()).touch(timeProvider.now());
        conversationRepository.save(updated);
        return toView(updated);
    }

    @Override
    @Transactional
    public ConversationView switchAgent(SwitchConversationAgentCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        if (!conversation.userId().equals(command.userId())
                || !conversation.tenantId().equals(command.tenantId())) {
            throw new BusinessException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        if (conversation.status() != ConversationStatus.ACTIVE) {
            throw new BusinessException(
                    "Conversation is closed",
                    HttpStatus.CONFLICT,
                    "CONVERSATION_CLOSED");
        }
        if (command.agentId().equals(conversation.agentId())) {
            return toView(conversation);
        }
        Conversation updated = conversation.switchAgent(command.agentId(), timeProvider.now());
        conversationRepository.save(updated);
        return toView(updated);
    }

    @Override
    @Transactional
    public ConversationView rename(RenameConversationCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        if (!java.util.Objects.equals(command.tenantId(), conversation.tenantId())
                || !java.util.Objects.equals(command.userId(), conversation.userId())) {
            throw new BusinessException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        if (conversation.status() != ConversationStatus.ACTIVE) {
            throw new BusinessException("Conversation is closed", HttpStatus.CONFLICT, "CONVERSATION_CLOSED");
        }
        String title = command.title();
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new BusinessException("Conversation title must contain 1 to 200 characters",
                    HttpStatus.BAD_REQUEST, "CONVERSATION_TITLE_INVALID");
        }
        if (conversation.title().equals(title.trim())) return toView(conversation);
        Conversation updated = conversation.rename(title, timeProvider.now());
        conversationRepository.save(updated);
        return toView(updated);
    }

    private Conversation requireConversationForUpdate(String id) {
        return conversationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("Conversation not found", HttpStatus.NOT_FOUND));
    }

    @Override
    @Transactional
    public ConversationView setActiveTask(SetConversationActiveTaskCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        if (!conversation.userId().equals(command.userId())
                || !conversation.tenantId().equals(command.tenantId())) {
            throw new BusinessException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        String taskId = normalizeOptional(command.taskId());
        validateActiveTask(
                conversation.tenantId(), conversation.userId(), conversation.projectId(),
                conversation.id(), taskId);
        Conversation updated = conversation.focusTask(taskId, timeProvider.now());
        conversationRepository.save(updated);
        return toView(updated);
    }

    @Override
    @Transactional
    public MessageView append(AppendMessageCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        if (conversation.status() == ConversationStatus.CLOSED) {
            throw new BusinessException("Conversation is closed", HttpStatus.CONFLICT);
        }
        Message message = new Message(
                idGenerator.nextId(),
                command.conversationId(),
                command.sequence(),
                command.role(),
                command.content(),
                timeProvider.now());
        messageRepository.save(message);
        conversationRepository.save(conversation.touch(message.createdAt()));
        return toView(message);
    }

    @Override
    @Transactional
    public synchronized ReservedReplyView reserveReply(
            String tenantId,
            String userId,
            String conversationId,
            String userContent) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .orElseThrow(() -> new BusinessException("Conversation not found", HttpStatus.NOT_FOUND));
        if (conversation.status() != ConversationStatus.ACTIVE) {
            throw new BusinessException("Conversation is closed", HttpStatus.CONFLICT, "CONVERSATION_CLOSED");
        }
        int userSequence = messageRepository.nextSequence(conversation.id());
        Instant now = timeProvider.now();
        Message userMessage = new Message(
                idGenerator.nextId(), conversation.id(), userSequence, "USER", userContent, now);
        Message reservation = new Message(
                idGenerator.nextId(), conversation.id(), userSequence + 1,
                "ASSISTANT_PENDING", "", now);
        messageRepository.save(userMessage);
        messageRepository.save(reservation);
        conversationRepository.save(conversation.touch(now));
        return new ReservedReplyView(toView(userMessage), reservation.id(), reservation.sequence());
    }

    @Override
    @Transactional
    public MessageView completeReply(
            String tenantId,
            String userId,
            String conversationId,
            String reservationId,
            String assistantContent) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .filter(value -> tenantId.equals(value.tenantId()) && userId.equals(value.userId()))
                .orElseThrow(() -> new BusinessException("Conversation not found", HttpStatus.NOT_FOUND));
        Message reserved = messageRepository.findById(reservationId)
                .filter(value -> conversation.id().equals(value.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Assistant reply reservation not found", HttpStatus.CONFLICT,
                        "CONVERSATION_REPLY_RESERVATION_MISSING"));
        if ("ASSISTANT".equals(reserved.role())) {
            if (!reserved.content().equals(assistantContent)) {
                throw new BusinessException(
                        "Assistant reply reservation has different completed content",
                        HttpStatus.CONFLICT, "CONVERSATION_REPLY_RESERVATION_CONFLICT");
            }
            return toView(reserved);
        }
        if (!"ASSISTANT_PENDING".equals(reserved.role()) && !"ASSISTANT_PARTIAL".equals(reserved.role())) {
            throw new BusinessException(
                    "Assistant reply reservation is not pending", HttpStatus.CONFLICT,
                    "CONVERSATION_REPLY_RESERVATION_MISSING");
        }
        Instant now = timeProvider.now();
        if (!messageRepository.completeReserved(reserved.id(), assistantContent, now)) {
            throw new BusinessException(
                    "Assistant reply reservation was already completed", HttpStatus.CONFLICT,
                    "CONVERSATION_REPLY_RESERVATION_CONFLICT");
        }
        conversationRepository.save(conversation.touch(now));
        return new MessageView(
                reserved.id(), reserved.conversationId(), reserved.sequence(),
                "ASSISTANT", assistantContent, now);
    }

    @Override
    public List<MessageView> messages(String conversationId) {
        requireConversation(conversationId);
        return recentMessages(conversationId, 200);
    }

    @Override @Transactional
    public void updateReplyDraft(String tenantId,String userId,String conversationId,String reservationId,String content) {
        if(content==null||content.isBlank())return;
        Conversation conversation=conversationRepository.findByIdForUpdate(conversationId)
                .filter(value->tenantId.equals(value.tenantId())&&userId.equals(value.userId()))
                .orElseThrow(()->new BusinessException("Conversation not found",HttpStatus.NOT_FOUND));
        messageRepository.findById(reservationId).filter(value->conversation.id().equals(value.conversationId()))
                .orElseThrow(()->new BusinessException("Reply reservation not found",HttpStatus.NOT_FOUND));
        messageRepository.updateReservedDraft(reservationId,content,timeProvider.now());
    }

    @Override public Optional<MessageView> findReply(String tenantId,String userId,String conversationId,String reservationId){
        var conversation=requireConversation(conversationId);
        if(!tenantId.equals(conversation.tenantId())||!userId.equals(conversation.userId()))throw new BusinessException("Conversation not found",HttpStatus.NOT_FOUND);
        return messageRepository.findById(reservationId).filter(message->conversationId.equals(message.conversationId()))
                .map(ConversationApplicationService::toView);
    }

    @Override
    public List<MessageView> recentMessages(String conversationId, int limit) {
        requireConversation(conversationId);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return messageRepository.findRecentByConversationId(conversationId, safeLimit).stream()
                .map(ConversationApplicationService::toView)
                .toList();
    }

    @Override
    public long messageCount(String conversationId) {
        requireConversation(conversationId);
        return messageRepository.countByConversationId(conversationId);
    }

    @Override
    @Transactional
    public void close(CloseConversationCommand command) {
        Conversation conversation = requireConversationForUpdate(command.conversationId());
        Conversation closed = new Conversation(
                conversation.id(),
                conversation.projectId(),
                conversation.projectDirectoryId(),
                conversation.taskId(),
                conversation.activeTaskId(),
                conversation.tenantId(),
                conversation.userId(),
                conversation.agentId(),
                conversation.title(),
                ConversationStatus.CLOSED,
                conversation.createdAt(),
                timeProvider.now());
        conversationRepository.save(closed);
    }

    @Override
    @Transactional
    public void delete(DeleteConversationCommand command) {
        requireConversation(command.conversationId());
        conversationRepository.deleteById(command.conversationId());
        messageRepository.deleteByConversationId(command.conversationId());
    }

    @Override
    public boolean isParticipant(String conversationId, String principalId) {
        return conversationRepository.findById(conversationId)
                .map(conversation -> principalId != null && principalId.equals(conversation.userId()))
                .orElse(false);
    }

    private Conversation requireConversation(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(
                        "Conversation not found: " + conversationId,
                        HttpStatus.NOT_FOUND));
    }

    private void validateActiveTask(
            String tenantId,
            String userId,
            String projectId,
            String conversationId,
            String taskId) {
        if (taskId == null) {
            return;
        }
        if (projectId == null) {
            if (taskApi == null) {
                throw new BusinessException(
                        "Task validation is unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "CONVERSATION_TASK_VALIDATION_UNAVAILABLE");
            }
            if (conversationId == null) {
                throw new BusinessException(
                        "A new Conversation requires a Project before binding a pre-existing Task",
                        HttpStatus.CONFLICT, "CONVERSATION_PROJECT_REQUIRED");
            }
            taskApi.getChatTask(new GetChatTaskQuery(
                    tenantId, userId, conversationId, taskId));
            return;
        }
        if (taskApi == null) {
            throw new BusinessException(
                    "Task validation is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CONVERSATION_TASK_VALIDATION_UNAVAILABLE");
        }
        taskApi.getTask(new GetTaskQuery(tenantId, userId, projectId, taskId));
    }


    private String resolveDirectory(StartConversationCommand command) {
        if (command.projectId() == null) {
            if (command.projectDirectoryId() != null) {
                throw new BusinessException(
                        "ProjectDirectory requires a Project", HttpStatus.CONFLICT,
                        "CONVERSATION_PROJECT_REQUIRED");
            }
            return null;
        }
        if (directoryApi == null) {
            return command.projectDirectoryId();
        }
        return directoryApi.resolveConversationDirectory(
                new ProjectDirectoryApplicationApi.ResolveConversationCommand(
                        command.tenantId(), command.userId(), command.projectId(),
                        command.projectDirectoryId())).id();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ConversationView toView(Conversation conversation) {
        return new ConversationView(
                conversation.id(),
                conversation.projectId(),
                conversation.projectDirectoryId(),
                conversation.taskId(),
                conversation.activeTaskId(),
                conversation.tenantId(),
                conversation.userId(),
                conversation.agentId(),
                conversation.title(),
                conversation.status(),
                conversation.createdAt(),
                conversation.updatedAt());
    }

    private static MessageView toView(Message message) {
        return new MessageView(
                message.id(),
                message.conversationId(),
                message.sequence(),
                message.role(),
                message.content(),
                message.createdAt());
    }
}
