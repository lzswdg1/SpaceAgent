package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.ChatRunLifecycleApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/chat")
public class PlatformChatRunLifecycleHttpController {
    private final ChatRunLifecycleApi lifecycle;
    public PlatformChatRunLifecycleHttpController(ChatRunLifecycleApi lifecycle){this.lifecycle=lifecycle;}
    @GetMapping("/conversations/{conversationId}/run")
    public ApiResponse<ChatRunLifecycleApi.Status> latest(@PathVariable String conversationId,Authentication auth){
        return ApiResponse.ok(lifecycle.latest(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),conversationId));
    }
    @GetMapping("/runs/{runId}/status")
    public ApiResponse<ChatRunLifecycleApi.Status> status(@PathVariable String runId,Authentication auth){
        return ApiResponse.ok(lifecycle.get(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),runId));
    }
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<ChatRunLifecycleApi.Status> cancel(@PathVariable String runId,Authentication auth){
        PlatformHttpSupport.requireWrite(auth);
        return ApiResponse.ok(lifecycle.cancel(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),runId));
    }
}
