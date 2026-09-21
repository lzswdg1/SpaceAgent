package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialMcpRegistryHttpGatewayTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final OfficialMcpRegistryHttpGateway gateway =
            new OfficialMcpRegistryHttpGateway(json, new McpToolingProperties());

    @Test
    void parsesOfficialEnvelopeKeepsOnlyFixedHttpsStreamableRemoteAndRedactsSecrets() {
        var page = gateway.parsePage("""
                {
                  "servers": [{
                    "server": {
                      "name": "io.github.example/weather",
                      "title": "Weather",
                      "description": "Weather through MCP",
                      "version": "1.2.3",
                      "$schema": "https://static.modelcontextprotocol.io/schema.json",
                      "repository": {"url": "https://github.com/example/weather", "source": "github"},
                      "remotes": [
                        {"type": "streamable-http", "url": "https://mcp.example.com/rpc",
                         "headers": [{"name": "Authorization", "isSecret": true,
                                      "value": "Bearer registry-secret", "default": "secret"}]},
                        {"type": "streamable-http", "url": "{baseUrl}/mcp"},
                        {"type": "sse", "url": "https://mcp.example.com/sse"}
                      ]
                    },
                    "_meta": {"io.modelcontextprotocol.registry/official": {
                      "status": "active", "publishedAt": "2026-09-01T00:00:00Z",
                      "updatedAt": "2026-09-02T00:00:00Z"
                    }, "vendor": {"apiKey": "should-not-persist"}}
                  }],
                  "metadata": {"nextCursor": "opaque-cursor"}
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(page.nextCursor()).isEqualTo("opaque-cursor");
        assertThat(page.servers()).hasSize(1);
        var server = page.servers().getFirst();
        assertThat(server.status()).isEqualTo(McpRegistryStatus.ACTIVE);
        assertThat(server.compatibility()).isEqualTo(McpRegistryCompatibility.SUPPORTED_REMOTE);
        assertThat(server.transports()).hasSize(1);
        assertThat(server.transports().getFirst().endpointUrl())
                .isEqualTo("https://mcp.example.com/rpc");
        assertThat(server.transports().getFirst().secretHeaders()).isTrue();
        assertThat(server.sanitizedManifestJson())
                .doesNotContain("registry-secret")
                .doesNotContain("should-not-persist")
                .contains("[REDACTED]");
        assertThat(server.transports().getFirst().headersJson())
                .doesNotContain("value")
                .doesNotContain("default");
    }

    @Test
    void rejectsMalformedRequiredIdentityRatherThanAdvancingTheWatermark() {
        assertThatThrownBy(() -> gateway.parsePage("""
                {"servers":[{"server":{"name":"invalid","description":"x","version":"latest"}}]}
                """.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(com.spaceagent.platform.tooling.domain.McpRegistryGatewayException.class)
                .hasMessageContaining("invalid");
    }
}
