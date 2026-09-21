package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.api.McpBindingValidationApplicationApi;
import com.spaceagent.platform.tooling.api.McpPromptApplicationApi;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.application.McpPromptApplicationService;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPromptApplicationServiceTest {
    @Test
    void validatesExactBindingSchemaAndBoundedTextContext() {
        Fixture fixture = fixture();
        when(fixture.gateway.listPrompts(any(), any())).thenReturn(List.of(prompt()));
        when(fixture.gateway.getPrompt(any(), any(), any(), any())).thenReturn(
                new McpRemoteToolGateway.RemotePromptResult(
                        "safe", List.of(new McpRemoteToolGateway.RemotePromptMessage(
                                "USER", "Summarize bounded MCP", true))));

        assertThat(fixture.service.list(new McpPromptApplicationApi.ListCommand(
                "tenant", "owner", binding()))).hasSize(1);
        McpPromptApplicationApi.PromptResult result = fixture.service.get(
                new McpPromptApplicationApi.GetCommand(
                        "tenant", "owner", binding(), "summarize",
                        Map.of("topic", "bounded MCP")));

        assertThat(result.messages()).singleElement()
                .extracting(McpPromptApplicationApi.MessageView::role)
                .isEqualTo("USER");
        assertThat(result.utf8Bytes()).isEqualTo(21);
        verify(fixture.validator, times(2)).validate(any());
    }

    @Test
    void rejectsUndeclaredMissingSecretAndUnsupportedPromptContent() {
        Fixture fixture = fixture();
        when(fixture.gateway.listPrompts(any(), any())).thenReturn(List.of(prompt()));

        assertThatThrownBy(() -> fixture.service.get(new McpPromptApplicationApi.GetCommand(
                "tenant", "owner", binding(), "summarize", Map.of())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.get(new McpPromptApplicationApi.GetCommand(
                "tenant", "owner", binding(), "summarize", Map.of("other", "value"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.get(new McpPromptApplicationApi.GetCommand(
                "tenant", "owner", binding(), "summarize", Map.of("topic", "api_key=hidden"))))
                .isInstanceOf(BusinessException.class);

        when(fixture.gateway.getPrompt(any(), any(), any(), any())).thenReturn(
                new McpRemoteToolGateway.RemotePromptResult(
                        "unsafe", List.of(new McpRemoteToolGateway.RemotePromptMessage(
                                "USER", null, false))));
        assertThatThrownBy(() -> fixture.service.get(new McpPromptApplicationApi.GetCommand(
                "tenant", "owner", binding(), "summarize", Map.of("topic", "safe"))))
                .isInstanceOf(BusinessException.class);
    }

    private static Fixture fixture() {
        McpMarketplaceRepository marketplace = mock(McpMarketplaceRepository.class);
        McpBindingValidationApplicationApi validator =
                mock(McpBindingValidationApplicationApi.class);
        McpConnectionAuthorizationService authorization =
                mock(McpConnectionAuthorizationService.class);
        McpRemoteToolGateway gateway = mock(McpRemoteToolGateway.class);
        McpConnection connection = connection();
        when(marketplace.findConnection("connection")).thenReturn(Optional.of(connection));
        when(authorization.authorize(connection)).thenReturn(
                new McpConnectionAuthorizationService.AuthorizedConnection(
                        connection, Map.of("access_token", "redacted")));
        return new Fixture(
                new McpPromptApplicationService(
                        marketplace, validator, authorization, gateway),
                validator, gateway);
    }

    private static McpRemoteToolGateway.RemotePrompt prompt() {
        return new McpRemoteToolGateway.RemotePrompt(
                "summarize", "Summarize", "safe", List.of(
                        new McpRemoteToolGateway.RemotePromptArgument(
                                "topic", "Topic", "Topic to summarize", true)));
    }

    private static McpPromptApplicationApi.Binding binding() {
        return new McpPromptApplicationApi.Binding(
                "installation", "connection", "server", "snapshot", 1,
                "sha256:" + "a".repeat(64), List.of("tool"));
    }

    private static McpConnection connection() {
        return new McpConnection(
                "connection", "installation", "tenant", "owner",
                "https://mcp.example", "encrypted", McpAuthType.NONE,
                McpConnectionState.ACTIVE, null, null, 1,
                Instant.EPOCH, Instant.EPOCH, null);
    }

    private record Fixture(
            McpPromptApplicationService service,
            McpBindingValidationApplicationApi validator,
            McpRemoteToolGateway gateway) {
    }
}
