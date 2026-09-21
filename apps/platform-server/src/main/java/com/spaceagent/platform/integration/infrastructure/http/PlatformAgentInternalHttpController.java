package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.agent.api.AgentApiKeyApplicationApi;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentRuntimeConfigurationView;
import com.spaceagent.platform.agent.api.VerifiedAgentApiKeyView;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal Agent runtime and API-key verification adapter. */
@RestController
@RequestMapping("/internal/agents")
public class PlatformAgentInternalHttpController {

    private final AgentApplicationApi agentApi;
    private final AgentApiKeyApplicationApi apiKeyApi;

    public PlatformAgentInternalHttpController(
            AgentApplicationApi agentApi,
            AgentApiKeyApplicationApi apiKeyApi) {
        this.agentApi = agentApi;
        this.apiKeyApi = apiKeyApi;
    }

    @GetMapping("/{agentId}/runtime-config")
    public ApiResponse<AgentRuntimeConfigurationView> runtimeConfiguration(
            @PathVariable String agentId,
            Authentication authentication) {
        return ApiResponse.ok(agentApi.runtimeConfiguration(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentId));
    }

    @PostMapping("/api-keys/verify")
    public ApiResponse<VerifiedAgentApiKeyView> verifyAgentApiKey(
            @Valid @RequestBody VerifyAgentApiKeyRequest request) {
        return apiKeyApi.verify(request.rawKey())
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(
                        "Invalid Agent API key",
                        HttpStatus.UNAUTHORIZED,
                        "AGENT_API_KEY_INVALID"));
    }

    public record VerifyAgentApiKeyRequest(@NotBlank String rawKey) {
    }
}
