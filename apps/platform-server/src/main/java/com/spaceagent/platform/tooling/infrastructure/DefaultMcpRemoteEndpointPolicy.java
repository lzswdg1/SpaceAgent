package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Fail-closed MCP endpoint policy; custom hosts require an operator allowlist entry. */
@Component
public class DefaultMcpRemoteEndpointPolicy implements McpRemoteEndpointPolicy {
    private final PublicEndpointResolver resolver;
    private final List<String> allowedHosts;

    /** Test convenience; production uses the configuration-aware constructor. */
    public DefaultMcpRemoteEndpointPolicy() {
        this(new PublicEndpointResolver(), List.of("*"));
    }

    @Autowired
    public DefaultMcpRemoteEndpointPolicy(
            PublicEndpointResolver resolver,
            McpToolingProperties properties) {
        this(resolver, properties.getAllowedHosts());
    }

    DefaultMcpRemoteEndpointPolicy(PublicEndpointResolver resolver, List<String> allowedHosts) {
        this.resolver = resolver;
        this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    public URI validate(String endpoint) {
        PublicEndpointResolver.ResolvedEndpoint resolved = resolver.resolve(endpoint);
        String host = resolved.uri().getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts.stream().anyMatch(value -> "*".equals(value)
                || host.equals(value)
                || (value.startsWith("*.") && host.endsWith(value.substring(1))));
        if (!allowed) {
            throw new IllegalArgumentException("MCP endpoint host is not operator-allowlisted");
        }
        return resolved.uri();
    }
}
