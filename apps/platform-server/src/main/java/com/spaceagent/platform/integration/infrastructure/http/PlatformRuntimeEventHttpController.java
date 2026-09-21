package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.RunEventPageView;
import com.spaceagent.platform.runtime.api.RunEventView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeOwnershipPort;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable RunEvent replay and SSE cursor adapter, safe to reconnect to any replica. */
@RestController
@RequestMapping("/api/v1/runtime/runs")
public class PlatformRuntimeEventHttpController {

    private static final int PAGE_LIMIT = 200;
    private static final long POLL_MILLIS = 200;

    private final RuntimeApplicationApi runtime;
    private final RuntimeOwnershipPort ownership;
    private final ExecutorService streamExecutor;
    private final PlatformRequestAdmissionService admission;

    public PlatformRuntimeEventHttpController(
            RuntimeApplicationApi runtime,
            RuntimeOwnershipPort ownership,
            @Qualifier("runtimeEventStreamExecutor") ExecutorService streamExecutor,
            PlatformRequestAdmissionService admission) {
        this.runtime = runtime;
        this.ownership = ownership;
        this.streamExecutor = streamExecutor;
        this.admission = admission;
    }

    @GetMapping("/{agentRunId}/events")
    public ApiResponse<RunEventPageView> events(
            @PathVariable String agentRunId,
            @RequestParam(defaultValue = "-1") long after,
            @RequestParam(defaultValue = "200") int limit,
            Authentication authentication) {
        requireObserve(agentRunId, authentication);
        return ApiResponse.ok(runtime.findEventsAfter(agentRunId, after, limit));
    }

    @GetMapping(value = "/{agentRunId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String agentRunId,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "15") int waitSeconds,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            Authentication authentication) {
        requireObserve(agentRunId, authentication);
        if (waitSeconds < 0 || waitSeconds > 30) {
            throw new BusinessException(
                    "waitSeconds must be between 0 and 30", HttpStatus.BAD_REQUEST);
        }
        long cursor = resolveCursor(after, lastEventId);
        var lease = admission.acquireSse(PlatformHttpSupport.userId(authentication));
        SseEmitter emitter = new SseEmitter((waitSeconds + 5L) * 1000L);
        try {
            streamExecutor.submit(() -> stream(agentRunId, cursor, waitSeconds, emitter, lease));
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
        return emitter;
    }

    private void stream(
            String agentRunId,
            long initialCursor,
            int waitSeconds,
            SseEmitter emitter,
            PlatformRequestAdmissionService.SseLease lease) {
        long cursor = initialCursor;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        try {
            while (true) {
                RunEventPageView page = runtime.findEventsAfter(agentRunId, cursor, PAGE_LIMIT);
                for (RunEventView event : page.events()) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name("runtime_event")
                            .data(event));
                    cursor = event.sequence();
                }
                if (page.terminal() || waitSeconds == 0 || System.nanoTime() >= deadline) {
                    emitter.send(SseEmitter.event().name("cursor").data(Map.of(
                            "agentRunId", agentRunId,
                            "nextSequence", cursor,
                            "terminal", page.terminal())));
                    emitter.complete();
                    return;
                }
                if (page.events().isEmpty()) {
                    Thread.sleep(POLL_MILLIS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interrupted);
        } catch (Exception error) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "code", "RUNTIME_EVENT_STREAM_ERROR",
                        "message", "Runtime stream failed",
                        "nextSequence", cursor)));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(error);
            }
        } finally {
            lease.close();
        }
    }

    private void requireObserve(String agentRunId, Authentication authentication) {
        String userId = PlatformHttpSupport.userId(authentication);
        String tenantId = PlatformHttpSupport.tenantId(authentication);
        if (!ownership.canObserve(agentRunId, tenantId, userId)) {
            throw new BusinessException("Agent run not found", HttpStatus.NOT_FOUND);
        }
    }

    private static long resolveCursor(Long after, String lastEventId) {
        long cursor = after == null ? -1 : after;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                cursor = Math.max(cursor, Long.parseLong(lastEventId.trim()));
            } catch (NumberFormatException error) {
                throw new BusinessException("Invalid Last-Event-ID", HttpStatus.BAD_REQUEST);
            }
        }
        if (cursor < -1) {
            throw new BusinessException("Runtime event cursor must be >= -1", HttpStatus.BAD_REQUEST);
        }
        return cursor;
    }
}
