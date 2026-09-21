package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.infrastructure.DefaultMcpRemoteEndpointPolicy;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import com.spaceagent.platform.tooling.infrastructure.PublicEndpointResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultMcpRemoteEndpointPolicyTest {
    private final DefaultMcpRemoteEndpointPolicy policy = new DefaultMcpRemoteEndpointPolicy();

    @Test
    void acceptsOnlyPublicHttpsEndpoints() {
        assertEquals("https://8.8.8.8/mcp", policy.validate("https://8.8.8.8/mcp").toString());
        assertThrows(IllegalArgumentException.class, () -> policy.validate("http://8.8.8.8/mcp"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://127.0.0.1/mcp"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://10.0.0.1/mcp"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://user@8.8.8.8/mcp"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://8.8.8.8/mcp#fragment"));
    }

    @Test
    void productionPolicyRequiresAnExplicitOperatorHost() {
        McpToolingProperties properties = new McpToolingProperties();
        properties.setAllowedHosts(java.util.List.of("8.8.8.8"));
        var restricted = new DefaultMcpRemoteEndpointPolicy(
                new PublicEndpointResolver(), properties);
        assertEquals("https://8.8.8.8/mcp", restricted.validate("https://8.8.8.8/mcp").toString());
        assertThrows(IllegalArgumentException.class,
                () -> restricted.validate("https://1.1.1.1/mcp"));
    }
}
