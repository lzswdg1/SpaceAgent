package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded public HTTP with per-request DNS pinning; the RestClient constructor is test-only. */
@Component
public class BoundedPublicHttpClient {
    private static final int HARD_MAX_BYTES = 10_000_000;
    private final PublicEndpointResolver resolver;
    private final McpRemoteEndpointPolicy testPolicy;
    private final RestClient testClient;

    @Autowired
    public BoundedPublicHttpClient(PublicEndpointResolver resolver) {
        this.resolver = resolver;
        this.testPolicy = null;
        this.testClient = null;
    }

    /** Supports MockRestServiceServer without weakening the production transport. */
    public BoundedPublicHttpClient(McpRemoteEndpointPolicy policy, RestClient testClient) {
        this.resolver = null;
        this.testPolicy = policy;
        this.testClient = testClient;
    }

    public Response get(URI requested, Map<String, String> headers, int maxBytes) {
        return execute(requested, headers, maxBytes, true);
    }

    /** Returns one DNS-pinned hop without following redirects. */
    public Response getRaw(URI requested, Map<String, String> headers, int maxBytes) {
        return execute(requested, headers, maxBytes, false);
    }

    public Response getRaw(URI requested, Map<String, String> headers, int maxBytes, Duration remaining) {
        return execute(requested, headers, maxBytes, false, remaining);
    }

    private Response execute(
            URI requested, Map<String, String> headers, int maxBytes, boolean rejectRedirect) {
        return execute(requested, headers, maxBytes, rejectRedirect, Duration.ofSeconds(30));
    }

    private Response execute(URI requested, Map<String, String> headers, int maxBytes,
            boolean rejectRedirect, Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) throw new IllegalStateException("HTTP deadline exceeded");
        int limit = Math.max(1, Math.min(maxBytes, HARD_MAX_BYTES));
        if (testClient != null) return testGet(requested, headers, limit, rejectRedirect);
        PublicEndpointResolver.ResolvedEndpoint resolved = resolver.resolve(requested.toString());
        Dns pinnedDns = hostname -> {
            if (!resolved.uri().getHost().equalsIgnoreCase(hostname)) {
                throw new java.net.UnknownHostException("Unapproved redirect host");
            }
            return resolved.addresses();
        };
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(pinnedDns)
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(20))
                .callTimeout(remaining.compareTo(Duration.ofSeconds(30)) < 0 ? remaining : Duration.ofSeconds(30))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .build();
        Request.Builder request = new Request.Builder()
                .url(resolved.uri().toString())
                .get()
                .header(HttpHeaders.ACCEPT,
                        "text/html,text/plain,application/json,application/xml,application/xhtml+xml")
                .header(HttpHeaders.USER_AGENT, "SpaceAgent-Tooling/1.0");
        safeHeaders(request, headers);
        try (okhttp3.Response response = client.newCall(request.build()).execute()) {
            if (rejectRedirect && response.isRedirect()) {
                throw new IllegalStateException("HTTP redirects are not allowed");
            }
            byte[] body;
            try (InputStream input = response.body().byteStream()) {
                body = input.readNBytes(limit + 1);
            }
            if (body.length > limit) throw new IllegalStateException("HTTP response exceeds limit");
            HttpHeaders responseHeaders = new HttpHeaders();
            response.headers().toMultimap().forEach(responseHeaders::put);
            return new Response(resolved.uri(), response.code(),
                    HttpHeaders.readOnlyHttpHeaders(responseHeaders), body);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Bounded HTTP request failed");
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private Response testGet(
            URI requested, Map<String, String> headers, int limit, boolean rejectRedirect) {
        URI uri = testPolicy.validate(requested.toString());
        try {
            return testClient.get().uri(uri)
                    .headers(values -> safeHeaders(values, headers))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (rejectRedirect && status >= 300 && status < 400) {
                            throw new IllegalStateException("HTTP redirects are not allowed");
                        }
                        byte[] body;
                        InputStream responseBody = response.getBody();
                        if (responseBody == null) {
                            body = new byte[0];
                        } else {
                            try (InputStream input = responseBody) {
                                body = input.readNBytes(limit + 1);
                            }
                        }
                        if (body.length > limit) {
                            throw new IllegalStateException("HTTP response exceeds limit");
                        }
                        return new Response(uri, status,
                                HttpHeaders.readOnlyHttpHeaders(response.getHeaders()), body);
                    });
        } catch (RuntimeException error) {
            if (error instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Bounded HTTP request failed");
        }
    }

    private static void safeHeaders(Request.Builder target, Map<String, String> headers) {
        if (headers == null) return;
        headers.forEach((name, value) -> {
            if (safeHeader(name, value)) target.header(name, value);
        });
    }

    private static void safeHeaders(HttpHeaders target, Map<String, String> headers) {
        target.set(HttpHeaders.ACCEPT,
                "text/html,text/plain,application/json,application/xml,application/xhtml+xml");
        target.set(HttpHeaders.USER_AGENT, "SpaceAgent-Tooling/1.0");
        if (headers == null) return;
        headers.forEach((name, value) -> {
            if (safeHeader(name, value)) target.set(name, value);
        });
    }

    private static boolean safeHeader(String name, String value) {
        return name != null && value != null && name.matches("[A-Za-z0-9-]{1,80}")
                && !name.equalsIgnoreCase(HttpHeaders.HOST)
                && !name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                && !name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION)
                && !value.contains("\r") && !value.contains("\n") && value.length() <= 4_096;
    }

    public record Response(URI uri, int status, HttpHeaders headers, byte[] body) {
        public MediaType mediaType() {
            return headers.getContentType();
        }
    }
}
