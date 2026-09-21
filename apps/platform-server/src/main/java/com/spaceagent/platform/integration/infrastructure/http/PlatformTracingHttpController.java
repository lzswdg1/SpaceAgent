package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.observability.api.TracingApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/tracing")
public class PlatformTracingHttpController {

    private final TracingApplicationApi tracing;

    public PlatformTracingHttpController(TracingApplicationApi tracing) {
        this.tracing = tracing;
    }

    @GetMapping("/traces")
    public ApiResponse<TracingApplicationApi.TraceListResponse> traces(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long minDurationMs,
            @RequestParam(required = false) Long maxDurationMs,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        return ApiResponse.ok(tracing.list(new TracingApplicationApi.TraceQuery(
                tenant(authentication), user(authentication), status, agentId, sessionId,
                keyword, minDurationMs, maxDurationMs, since, until, limit, offset)));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<TracingApplicationApi.TraceDetailResponse> trace(
            @PathVariable UUID traceId,
            Authentication authentication) {
        return ApiResponse.ok(tracing.get(new TracingApplicationApi.TraceByIdQuery(
                tenant(authentication), user(authentication), traceId.toString())));
    }

    @GetMapping("/stats")
    public ApiResponse<TracingApplicationApi.TraceStatsResponse> stats(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long minDurationMs,
            @RequestParam(required = false) Long maxDurationMs,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(defaultValue = "7d") String range,
            Authentication authentication) {
        return ApiResponse.ok(tracing.stats(new TracingApplicationApi.TraceStatsQuery(
                tenant(authentication), user(authentication), status, agentId, sessionId,
                keyword, minDurationMs, maxDurationMs, since, until, range)));
    }

    private String tenant(Authentication authentication) {
        return PlatformHttpSupport.tenantId(authentication);
    }

    private String user(Authentication authentication) {
        return PlatformHttpSupport.userId(authentication);
    }
}
