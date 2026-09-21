package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.ChatExecutionCommand;
import com.spaceagent.platform.runtime.api.ChatExecutionView;
import com.spaceagent.platform.runtime.api.ChatApprovalResumeCommand;
import com.spaceagent.platform.runtime.api.ChatPlanResumeCommand;
import com.spaceagent.platform.runtime.api.ChatToolReconciliationCommand;
import com.spaceagent.platform.runtime.api.ChatRecoveryCommand;
import com.spaceagent.platform.runtime.api.ChatRecoveryView;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.ChatRuntimeEvent;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Chat HTTP/SSE compatibility adapter. Runtime is the only execution coordinator;
 * this controller imports no conversation, inference, tooling, or persistence types.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class PlatformChatHttpController {

    private static final long SSE_TIMEOUT_MILLIS = 665_000L;

    private final ChatRuntimeApplicationApi chatRuntimeApi;
    private final ExecutorService streamExecutor;
    private final PlatformRequestAdmissionService admission;

    public PlatformChatHttpController(
            ChatRuntimeApplicationApi chatRuntimeApi,
            @Qualifier("runtimeEventStreamExecutor") ExecutorService streamExecutor,
            PlatformRequestAdmissionService admission) {
        this.chatRuntimeApi = chatRuntimeApi;
        this.streamExecutor = streamExecutor;
        this.admission = admission;
    }

    @PostMapping("/messages")
    public ApiResponse<ChatMessageResponse> chat(
            @Valid @RequestBody ChatMessageRequest request,
            @RequestHeader(value = "X-Agent-Id", required = false) String authorizedAgentId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(ChatMessageResponse.from(execute(request, authorizedAgentId, authentication)));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody ChatMessageRequest request,
            @RequestHeader(value = "X-Agent-Id", required = false) String authorizedAgentId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        var lease = admission.acquireSse(PlatformHttpSupport.userId(authentication));
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        try {
            streamExecutor.submit(() -> streamExecution(
                    emitter, request, authorizedAgentId, authentication, lease));
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
        return emitter;
    }

    private void streamExecution(
            SseEmitter emitter,
            ChatMessageRequest request,
            String authorizedAgentId,
            Authentication authentication,
            PlatformRequestAdmissionService.SseLease lease) {
        var connected = new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        try {
            ChatExecutionView execution = chatRuntimeApi.executeStreaming(
                    new ChatExecutionCommand(
                            PlatformHttpSupport.tenantId(authentication),
                            PlatformHttpSupport.userId(authentication),
                            request.conversationId(),
                            resolveAgentId(request.agentId(), authorizedAgentId),
                            request.message(), request.modelId(), request.imageIds(), request.workspaceId()),
                    new ChatRuntimeApplicationApi.ChatStreamObserver() {
                        @Override
                        public void onRuntimeEvent(ChatRuntimeEvent event) {
                            sendUnchecked(emitter, event.type(), event.data(), connected);
                        }

                        @Override
                        public void onReasoningDelta(String content) {
                            sendUnchecked(emitter, "reasoning_delta", Map.of("content", content), connected);
                        }

                        @Override
                        public void onContentDelta(String content) {
                            sendUnchecked(emitter, "delta", Map.of("content", content), connected);
                        }
                    });
            ChatMessageResponse response = ChatMessageResponse.from(execution);
            if (execution.waiting()) {
                Map<String, Object> suspended = new LinkedHashMap<>();
                suspended.put("conversationId", response.conversationId());
                suspended.put("agentRunId", response.agentRunId());
                suspended.put("executionState", response.executionState());
                suspended.put("toolCallId", response.pendingToolCallId());
                suspended.put("toolName", response.pendingToolName());
                if (response.rootTaskId() != null) {
                    suspended.put("rootTaskId", response.rootTaskId());
                    suspended.put("rootTaskState", response.rootTaskState());
                }
                if (response.taskPlanId() != null) {
                    suspended.put("taskPlanId", response.taskPlanId());
                    suspended.put("taskPlanState", response.taskPlanState());
                }
                if (response.pendingApprovalId() != null) {
                    suspended.put("approvalId", response.pendingApprovalId());
                }
                if (response.pendingToolRevision() != null) {
                    suspended.put("toolRevision", response.pendingToolRevision());
                }
                send(emitter, "suspended", suspended);
            } else {
                send(emitter, "done", donePayload(response));
            }
            emitter.complete();
        } catch (Exception error) {
            try {
                send(emitter, "error", errorPayload(error));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(error);
            }
        } finally {
            lease.close();
        }
    }

    @PostMapping("/runs/{agentRunId}/recover")
    public ApiResponse<ChatRecoveryView> recover(
            @PathVariable String agentRunId,
            @RequestBody(required = false) RecoveryRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(chatRuntimeApi.recover(new ChatRecoveryCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentRunId,
                request == null ? null : request.reason())));
    }

    @PostMapping("/runs/{agentRunId}/resume-approval")
    public ApiResponse<ChatMessageResponse> resumeApproval(
            @PathVariable String agentRunId,
            @Valid @RequestBody ApprovalResumeRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(ChatMessageResponse.from(chatRuntimeApi.resumeApproval(
                new ChatApprovalResumeCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication),
                        agentRunId, request.approvalId()))));
    }

    @PostMapping("/runs/{agentRunId}/resume-plan")
    public ApiResponse<ChatMessageResponse> resumePlan(
            @PathVariable String agentRunId,
            @Valid @RequestBody PlanResumeRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(ChatMessageResponse.from(chatRuntimeApi.resumePlan(
                new ChatPlanResumeCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), agentRunId,
                        request.taskPlanId()))));
    }

    @PostMapping("/runs/{agentRunId}/tools/{toolCallId}/reconcile")
    public ApiResponse<ChatMessageResponse> reconcileTool(
            @PathVariable String agentRunId,
            @PathVariable String toolCallId,
            @Valid @RequestBody ToolReconciliationRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(ChatMessageResponse.from(chatRuntimeApi.reconcileTool(
                new ChatToolReconciliationCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), agentRunId, toolCallId,
                        request.expectedRevision(), request.reason()))));
    }

    private ChatExecutionView execute(
            ChatMessageRequest request,
            String authorizedAgentId,
            Authentication authentication) {
        return chatRuntimeApi.execute(new ChatExecutionCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.conversationId(),
                resolveAgentId(request.agentId(), authorizedAgentId),
                request.message(),
                request.modelId(),
                request.imageIds(), request.workspaceId()));
    }

    private String resolveAgentId(String requestedAgentId, String authorizedAgentId) {
        if (authorizedAgentId == null || authorizedAgentId.isBlank()) {
            return requestedAgentId;
        }
        if (requestedAgentId != null && !requestedAgentId.isBlank()
                && !authorizedAgentId.equals(requestedAgentId)) {
            throw new BusinessException(
                    "Agent API key is not allowed to call this agent",
                    HttpStatus.FORBIDDEN);
        }
        return authorizedAgentId;
    }

    private Map<String, Object> donePayload(ChatMessageResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", response.conversationId());
        payload.put("agentRunId", response.agentRunId());
        payload.put("memoryUpdated", response.memoryUpdated());
        payload.put("ragUsed", response.ragUsed());
        payload.put("retrievedChunkCount", response.retrievedChunkCount());
        payload.put("citations", response.citations());
        payload.put("knowledgeCitations", response.knowledgeCitations());
        payload.put("inputTokenCount", response.inputTokenCount());
        payload.put("outputTokenCount", response.outputTokenCount());
        payload.put("totalTokenCount", response.totalTokenCount());
        payload.put("usageSource", "provider");
        payload.put("eventCount", response.runtimeEvents().size());
        if (response.rootTaskId() != null) payload.put("rootTaskId", response.rootTaskId());
        if (response.rootTaskState() != null) payload.put("rootTaskState", response.rootTaskState());
        if (response.taskPlanId() != null) payload.put("taskPlanId", response.taskPlanId());
        if (response.taskPlanState() != null) payload.put("taskPlanState", response.taskPlanState());
        return payload;
    }

    private Map<String, Object> errorPayload(Exception error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (error instanceof BusinessException businessException) {
            payload.put("message", businessException.getMessage());
            payload.put("code", businessException.getCode());
            payload.put("status", businessException.getStatus().value());
        } else {
            payload.put("message", "Chat stream failed");
            payload.put("code", "CHAT_STREAM_ERROR");
            payload.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
        return payload;
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private void sendUnchecked(SseEmitter emitter, String event, Object data, java.util.concurrent.atomic.AtomicBoolean connected) {
        if (!connected.get()) return;
        try {
            send(emitter, event, data);
        } catch (IOException | IllegalStateException exception) {
            // Connection loss is not a user cancellation. Finish/persist the already admitted Run.
            connected.set(false);
        }
    }

    public record ChatMessageRequest(
            String conversationId,
            String agentId,
            @NotBlank(message = "message must not be blank")
            @Size(max = 4000, message = "message must be at most 4000 characters")
            String message,
            String modelId,
            List<String> imageIds,
            @Size(max = 36) String workspaceId) {
    }

    public record RecoveryRequest(String reason) {
    }

    public record ApprovalResumeRequest(
            @NotBlank(message = "approvalId must not be blank") String approvalId) {
    }

    public record ToolReconciliationRequest(
            @Min(1) long expectedRevision,
            @NotBlank @Size(max = 1_000) String reason) {
    }

    public record ChatMessageResponse(
            String conversationId,
            String agentRunId,
            String assistantMessage,
            boolean memoryUpdated,
            int recalledMemoryCount,
            List<String> recalledMemories,
            boolean ragUsed,
            int retrievedChunkCount,
            List<String> citations,
            List<String> knowledgeCitations,
            int inputTokenCount,
            int outputTokenCount,
            int totalTokenCount,
            List<String> events,
            List<ChatRuntimeEvent> runtimeEvents,
            String reasoningContent,
            String executionState,
            String pendingApprovalId,
            String pendingToolCallId,
            String pendingToolName,
            Long pendingToolRevision,
            String rootTaskId,
            String rootTaskState,
            String taskPlanId,
            String taskPlanState) {

        static ChatMessageResponse from(ChatExecutionView execution) {
            return new ChatMessageResponse(
                    execution.conversationId(),
                    execution.agentRunId(),
                    execution.assistantMessage(),
                    execution.memoryUpdated(),
                    execution.recalledMemoryCount(),
                    execution.recalledMemories(),
                    execution.ragUsed(),
                    execution.retrievedChunkCount(),
                    execution.citations(),
                    execution.citations(),
                    execution.inputTokenCount(),
                    execution.outputTokenCount(),
                    execution.inputTokenCount() + execution.outputTokenCount(),
                    execution.events().stream().map(ChatRuntimeEvent::type).toList(),
                    execution.events(),
                    execution.reasoningContent(),
                    execution.executionState(),
                    execution.pendingApprovalId(),
                    execution.pendingToolCallId(),
                    execution.pendingToolName(),
                    execution.pendingToolRevision(),
                    execution.rootTaskId(),
                    execution.rootTaskState(),
                    execution.taskPlanId(),
                    execution.taskPlanState());
        }
    }

    public record PlanResumeRequest(@jakarta.validation.constraints.NotBlank String taskPlanId) { }
}
