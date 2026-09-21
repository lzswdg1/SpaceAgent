package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.infrastructure.ConfiguredMcpOAuthClientRegistrationProvider;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredMcpOAuthClientRegistrationProviderTest {
    @Test
    void loadsExactPreRegisteredClientAndIgnoresEmptyComposeSlot() {
        McpToolingProperties properties = new McpToolingProperties();
        properties.getOauth().setClients(List.of(
                new McpToolingProperties.Client(),
                client("acme", "https://login.acme.example/oauth", "client-secret")));

        var provider = new ConfiguredMcpOAuthClientRegistrationProvider(properties);

        assertThat(provider.authorizationServers())
                .containsExactlyInAnyOrder("https://login.acme.example/oauth");
        assertThat(provider.findById("acme")).get().satisfies(registration -> {
            assertThat(registration.authenticationMethod())
                    .isEqualTo(McpOAuthClientAuthenticationMethod.CLIENT_SECRET_POST);
            assertThat(registration.scopes()).containsExactlyInAnyOrder("tools.read", "tools.call");
            assertThat(registration.allowedRedirectUris())
                    .containsExactlyInAnyOrder("https://app.example/mcp/oauth/callback");
        });
    }

    @Test
    void rejectsDuplicateIssuerAndMissingSecret() {
        McpToolingProperties duplicate = new McpToolingProperties();
        duplicate.getOauth().setClients(List.of(
                client("one", "https://login.acme.example", "one-secret"),
                client("two", "https://login.acme.example/", "two-secret")));
        assertThatThrownBy(() -> new ConfiguredMcpOAuthClientRegistrationProvider(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");

        McpToolingProperties missingSecret = new McpToolingProperties();
        missingSecret.getOauth().setClients(List.of(
                client("broken", "https://login.example", "")));
        assertThatThrownBy(() -> new ConfiguredMcpOAuthClientRegistrationProvider(missingSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret");
    }

    private static McpToolingProperties.Client client(
            String id, String authorizationServer, String secret) {
        McpToolingProperties.Client value = new McpToolingProperties.Client();
        value.setId(id);
        value.setAuthorizationServer(authorizationServer);
        value.setClientId("spaceagent-client");
        value.setClientSecret(secret);
        value.setAuthenticationMethod("client_secret_post");
        value.setScopes(List.of("tools.read", "tools.call"));
        value.setAllowedRedirectUris(List.of("https://app.example/mcp/oauth/callback"));
        return value;
    }
}
