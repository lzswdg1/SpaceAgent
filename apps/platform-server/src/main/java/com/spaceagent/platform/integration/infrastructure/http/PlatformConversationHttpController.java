package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.api.DeleteConversationCommand;
import com.spaceagent.platform.conversation.api.MessageView;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.conversation.api.SwitchConversationAgentCommand;
import com.spaceagent.platform.conversation.api.RenameConversationCommand;
import com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.api.PageResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Conversation/message HTTP adapter backed by the public conversation application API. */
@RestController
@RequestMapping("/api/v1")
public class PlatformConversationHttpController {

    private final ConversationApplicationApi conversationApi;
    private final AgentApplicationApi agentApi;

    public PlatformConversationHttpController(
            ConversationApplicationApi conversationApi,
            AgentApplicationApi agentApi) {
        this.conversationApi = conversationApi;
        this.agentApi = agentApi;
    }

    @GetMapping("/chat/conversations")
    public ApiResponse<PageResponse<ConversationSummaryResponse>> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(defaultValue = "") String query,
            Authentication authentication) {
        String userId = PlatformHttpSupport.userId(authentication);
        String tenantId = PlatformHttpSupport.tenantId(authentication);
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(1, Math.min(size, 200));
        var result = conversationApi.pageByScope(
                tenantId, userId, normalizedPage, normalizedSize,scope,query);
        List<ConversationSummaryResponse> items = result.items().stream()
                .map(ConversationSummaryResponse::from)
                .toList();
        return ApiResponse.ok(new PageResponse<>(
                items, normalizedPage, normalizedSize, result.total()));
    }

    @PostMapping("/chat/conversations")
    public ApiResponse<ConversationResponse> createConversation(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        ConversationView created = conversationApi.start(new StartConversationCommand(
                request.projectId(),
                request.projectDirectoryId(),
                null,
                request.activeTaskId(),
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.agentId(),
                request.name() == null || request.name().isBlank() ? "New conversation" : request.name()));
        return ApiResponse.ok(ConversationResponse.from(created));
    }

    @GetMapping("/projects/{projectId}/directories/{directoryId}/conversations")
    public ApiResponse<PageResponse<ConversationSummaryResponse>> listDirectoryConversations(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(1, Math.min(size, 200));
        var result = conversationApi.pageByProjectDirectory(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId,
                normalizedPage, normalizedSize);
        return ApiResponse.ok(new PageResponse<>(
                result.items().stream().map(ConversationSummaryResponse::from).toList(),
                normalizedPage, normalizedSize, result.total()));
    }

    @PutMapping("/chat/conversations/{conversationId}/active-task")
    public ApiResponse<ConversationResponse> setActiveTask(
            @PathVariable String conversationId,
            @RequestBody ActiveTaskRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        ConversationView updated = conversationApi.setActiveTask(
                new SetConversationActiveTaskCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication),
                        conversationId,
                        request == null ? null : request.taskId()));
        return ApiResponse.ok(ConversationResponse.from(updated));
    }

    @PutMapping("/chat/conversations/{conversationId}/agent")
    public ApiResponse<ConversationResponse> switchAgent(
            @PathVariable String conversationId,
            @Valid @RequestBody AgentSwitchRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        String tenantId = PlatformHttpSupport.tenantId(authentication);
        String userId = PlatformHttpSupport.userId(authentication);
        var agent = agentApi.findById(request.agentId())
                .filter(candidate -> tenantId.equals(candidate.tenantId()))
                .filter(candidate -> userId.equals(candidate.ownerId()))
                .filter(candidate -> candidate.status() == AgentDefinitionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("Agent not found", HttpStatus.NOT_FOUND));
        ConversationView updated = conversationApi.switchAgent(new SwitchConversationAgentCommand(
                tenantId,
                userId,
                conversationId,
                agent.id()));
        return ApiResponse.ok(ConversationResponse.from(updated));
    }

    @PutMapping("/chat/conversations/{conversationId}/title")
    public ApiResponse<ConversationSummaryResponse> renameConversation(
            @PathVariable String conversationId, @Valid @RequestBody RenameRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(ConversationSummaryResponse.from(conversationApi.rename(
                new RenameConversationCommand(PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), conversationId, request.name()))));
    }

    @GetMapping("/chat/conversations/{conversationId}")
    public ApiResponse<ConversationResponse> getConversation(
            @PathVariable String conversationId,
            Authentication authentication) {
        return ApiResponse.ok(ConversationResponse.from(requireConversation(conversationId, authentication)));
    }

    @GetMapping("/chat/conversations/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> messages(
            @PathVariable String conversationId,
            Authentication authentication) {
        requireConversation(conversationId, authentication);
        return ApiResponse.ok(conversationApi.messages(conversationId).stream()
                .map(MessageResponse::from)
                .toList());
    }

    @DeleteMapping("/chat/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(
            @PathVariable String conversationId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireConversation(conversationId, authentication);
        conversationApi.delete(new DeleteConversationCommand(conversationId));
        return ApiResponse.ok(null);
    }

    @GetMapping("/chat/conversations/{conversationId}/messages/page")
    public ApiResponse<ConversationApplicationApi.MessagePageView> messagePage(@PathVariable String conversationId,
            @RequestParam(required=false) Integer beforeSequence,@RequestParam(defaultValue="50") int size,Authentication authentication) {
        return ApiResponse.ok(conversationApi.messagePage(PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),conversationId,beforeSequence,size));
    }

    private ConversationView requireConversation(String conversationId, Authentication authentication) {
        ConversationView conversation = conversationApi.find(conversationId)
                .orElseThrow(() -> new BusinessException("Conversation not found", HttpStatus.NOT_FOUND));
        if (!PlatformHttpSupport.userId(authentication).equals(conversation.userId())
                || !PlatformHttpSupport.tenantId(authentication).equals(conversation.tenantId())) {
            throw new BusinessException("Conversation not found", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    public record CreateConversationRequest(
            @NotBlank String agentId,
            @Size(max = 200) String name,
            String projectId,
            String projectDirectoryId,
            String activeTaskId) {
    }

    public record ActiveTaskRequest(String taskId) {
    }

    public record AgentSwitchRequest(@NotBlank String agentId) {
    }

    public record RenameRequest(@NotBlank @Size(max = 200) String name) { }

    public record ConversationResponse(
            String conversationId,
            String agentId,
            String userId,
            String projectId,
            String projectDirectoryId,
            String activeTaskId,
            String status,
            Instant startedAt,
            Instant lastMessageAt,
            List<MessageResponse> messages,
            int messagePage,
            int messageSize,
            long messageTotal,
            String title) {

        static ConversationResponse from(ConversationView conversation) {
            return new ConversationResponse(
                    conversation.id(),
                    conversation.agentId(),
                    conversation.userId(),
                    conversation.projectId(),
                    conversation.projectDirectoryId(),
                    conversation.activeTaskId(),
                    conversation.status().name(),
                    conversation.createdAt(),
                    conversation.updatedAt(),
                    List.of(),
                    1,
                    0,
                    0,
                    conversation.title());
        }
    }

    public record ConversationSummaryResponse(
            String id,
            String agentId,
            String userId,
            String activeTaskId,
            String projectId,
            String projectDirectoryId,
            String title,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static ConversationSummaryResponse from(ConversationView conversation) {
            return new ConversationSummaryResponse(
                    conversation.id(),
                    conversation.agentId(),
                    conversation.userId(),
                    conversation.activeTaskId(),
                    conversation.projectId(),
                    conversation.projectDirectoryId(),
                    conversation.title(),
                    conversation.status().name(),
                    conversation.createdAt(),
                    conversation.updatedAt());
        }
    }

    public record MessageResponse(String role, String content, Instant createdAt) {

        static MessageResponse from(MessageView message) {
            return new MessageResponse(message.role(), message.content(), message.createdAt());
        }
    }

}
