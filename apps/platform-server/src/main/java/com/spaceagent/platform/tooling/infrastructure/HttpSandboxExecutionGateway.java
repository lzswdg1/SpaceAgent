package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP client for the Python sandbox worker. The domain port keeps the worker
 * replaceable; this adapter owns transport details only.
 */
@Component
@ConditionalOnProperty(prefix = "platform.sandbox", name = "mode", havingValue = "http")
public class HttpSandboxExecutionGateway implements SandboxExecutionGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String bearer;
    private final int maxResponseBytes;
    @Autowired
    private com.spaceagent.platform.tooling.domain.SandboxResourceObservationPort resourceObservations;

    @Autowired
    public HttpSandboxExecutionGateway(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            SandboxProperties properties) {
        this(restClientBuilder, objectMapper, properties, true);
    }

    HttpSandboxExecutionGateway(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            SandboxProperties properties,
            boolean boundedClient) {
        URI baseUri = baseUri(properties.getBaseUrl());
        String token = properties.getInternalToken() == null
                ? "" : properties.getInternalToken().trim();
        if (token.length() < 32 || token.length() > 512
                || token.contains("\r") || token.contains("\n")) {
            throw new IllegalStateException(
                    "Sandbox internal token must contain 32-512 safe characters");
        }
        int connectSeconds = bound(properties.getConnectTimeoutSeconds(), 1, 30);
        int requestSeconds = bound(properties.getRequestTimeoutSeconds(), 1, 660);
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(baseUri.toString());
        if (boundedClient) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connectSeconds))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(Duration.ofSeconds(requestSeconds));
            builder.requestFactory(factory);
        }
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.bearer = "Bearer " + token;
        this.maxResponseBytes = bound(properties.getMaxResponseBytes(), 1_024, 2_000_000);
    }

    @Override
    public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(request);
            byte[] responseBody = restClient.post()
                    .uri("/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .headers(headers -> W3CTraceContextPropagator.getInstance().inject(
                            Context.current(), headers, (carrier, key, value) -> carrier.set(key, value)))
                    .body(requestBody)
                    .exchange((requestSpec, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw new IllegalStateException("Sandbox worker rejected request");
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null
                                || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                            throw new IllegalStateException("Sandbox worker returned invalid content");
                        }
                        try (InputStream input = response.getBody()) {
                            byte[] body = input.readNBytes(maxResponseBytes + 1);
                            if (body.length > maxResponseBytes) {
                                throw new IllegalStateException(
                                        "Sandbox worker response exceeds limit");
                            }
                            return body;
                        }
                    });
            if (responseBody == null) {
                throw new IllegalStateException("Sandbox worker returned no result");
            }
            var result = objectMapper.readValue(responseBody, SandboxExecutionResponse.class);
            if (!request.executionId().equals(result.executionId()) || !request.agentRunId().equals(result.agentRunId())
                    || !request.toolCallId().equals(result.toolCallId()) || result.metadata() == null)
                throw new IllegalStateException("Sandbox response correlation failed");
            if (resourceObservations != null && result.metadata().resourceMetrics() != null) {
                try { resourceObservations.record(request, result.metadata().resourceMetrics()); }
                catch (RuntimeException ignored) { /* Observation loss is not execution uncertainty or permission to retry. */ }
            }
            return result;
        } catch (Exception error) {
            throw new SandboxExecutionUnavailableException(
                    "Sandbox worker unavailable or returned an invalid result", error);
        }
    }

    private static URI baseUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim()).normalize();
            boolean scheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean path = uri.getPath() == null || uri.getPath().isBlank()
                    || "/".equals(uri.getPath());
            if (!scheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !path) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException error) {
            throw new IllegalStateException("Sandbox base URL must be an HTTP service root");
        }
    }

    private static int bound(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
