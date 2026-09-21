package com.spaceagent.platform.tooling.infrastructure;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Resolves and validates the exact addresses that a public HTTPS client will use. */
@Component
public class PublicEndpointResolver {
    public ResolvedEndpoint resolve(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(uri.getHost()));
            if (addresses.isEmpty() || addresses.stream().anyMatch(PublicEndpointResolver::blocked)) {
                throw new IllegalArgumentException();
            }
            return new ResolvedEndpoint(uri, List.copyOf(addresses));
        } catch (Exception error) {
            throw new IllegalArgumentException("Endpoint must resolve to a public HTTPS address");
        }
    }

    static boolean blocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || uniqueLocal;
    }

    public record ResolvedEndpoint(URI uri, List<InetAddress> addresses) {
    }
}
