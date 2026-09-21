package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.observability.api.ObservabilityApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/monitoring")
public class PlatformObservabilityHttpController {

    private final ObservabilityApplicationApi api;

    public PlatformObservabilityHttpController(ObservabilityApplicationApi api) {
        this.api = api;
    }

    @GetMapping("/overview")
    public ApiResponse<ObservabilityApplicationApi.OverviewResponse> overview(
            @RequestParam(defaultValue = "7d") String range,
            Authentication authentication) {
        return ApiResponse.ok(api.overview(new ObservabilityApplicationApi.MonitoringQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                range)));
    }

    @GetMapping("/usage")
    public ApiResponse<ObservabilityApplicationApi.UsageResponse> usage(
            @RequestParam(defaultValue = "7d") String range,
            @RequestParam(defaultValue = "agent") String groupBy,
            Authentication authentication) {
        return ApiResponse.ok(api.usage(new ObservabilityApplicationApi.UsageQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                range,
                groupBy)));
    }

    @GetMapping("/realtime")
    public ApiResponse<ObservabilityApplicationApi.RealtimeStatus> realtime(
            Authentication authentication) {
        return ApiResponse.ok(api.realtime(new ObservabilityApplicationApi.ActorQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication))));
    }

    /** Independent lifetime Agent ranking; no time-range parameter is applied. */
    @GetMapping("/usage/agents")
    public ApiResponse<ObservabilityApplicationApi.UsageResponse> agentUsage(
            Authentication authentication) {
        return ApiResponse.ok(api.usage(new ObservabilityApplicationApi.UsageQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), "all", "agent")));
    }

    @GetMapping("/sessions")
    public ApiResponse<ObservabilityApplicationApi.SessionsResponse> sessions(
            @RequestParam(defaultValue = "7d") String range,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            Authentication authentication) {
        return ApiResponse.ok(api.sessions(new ObservabilityApplicationApi.SessionsQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                range,
                status,
                page,
                pageSize)));
    }

    @PostMapping("/sessions/{agentRunId}/stop")
    public ApiResponse<ObservabilityApplicationApi.SessionView> stop(
            @PathVariable String agentRunId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.stopSession(new ObservabilityApplicationApi.StopSessionCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentRunId)));
    }

    @PostMapping("/sessions/batch-stop")
    public ApiResponse<ObservabilityApplicationApi.BatchStopView> batchStop(
            @Valid @RequestBody BatchStopRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.stopSessions(
                new ObservabilityApplicationApi.BatchStopSessionsCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication),
                        request.sessionIds())));
    }

    public record BatchStopRequest(
            @NotEmpty @Size(max = 100) List<@Size(min = 1, max = 80) String> sessionIds) {}
}
