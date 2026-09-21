package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import com.spaceagent.platform.tooling.domain.SandboxResourcePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSandboxExecutionGatewayTest {
    private static final String TOKEN =
            "sandbox-http-test-token-0123456789-abcdef";

    @Test
    void sendsAuthenticatedBoundedRequestAndParsesCorrelatedResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new HttpSandboxExecutionGateway(
                builder, new ObjectMapper(), properties(), false);
        server.expect(requestTo("http://sandbox.test:9200/execute"))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("traceparent",
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))
                .andRespond(withSuccess("""
                        {"executionId":"exec","agentRunId":"run","toolCallId":"call",
                         "exitStatus":0,"status":"SUCCEEDED","stdout":"ok\\n","stderr":"",
                         "artifactRefs":[],"metadata":{"startedAtEpochMs":1,
                         "completedAtEpochMs":2,"wallTimeMs":1,"timedOut":false,
                         "outputBytes":3},"error":null}
                        """, MediaType.APPLICATION_JSON));

        var parent = Span.wrap(SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault()));
        com.spaceagent.platform.tooling.domain.SandboxExecutionResponse result;
        try (var ignored = Context.current().with(parent).makeCurrent()) {
            result = gateway.execute(request());
        }

        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        assertThat(result.stdout()).isEqualTo("ok\n");
        server.verify();
    }

    @Test
    void rejectsUnsafeConfigurationBeforeDispatch() {
        SandboxProperties missingToken = properties();
        missingToken.setInternalToken("short");
        assertThatThrownBy(() -> new HttpSandboxExecutionGateway(
                RestClient.builder(), new ObjectMapper(), missingToken, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal token");

        SandboxProperties unsafeUrl = properties();
        unsafeUrl.setBaseUrl("http://user:pass@sandbox.test:9200/path");
        assertThatThrownBy(() -> new HttpSandboxExecutionGateway(
                RestClient.builder(), new ObjectMapper(), unsafeUrl, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base URL");
    }

    @Test
    void oversizedWorkerResponseBecomesUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SandboxProperties properties = properties();
        properties.setMaxResponseBytes(1_024);
        var gateway = new HttpSandboxExecutionGateway(
                builder, new ObjectMapper(), properties, false);
        server.expect(requestTo("http://sandbox.test:9200/execute"))
                .andRespond(withSuccess("x".repeat(1_025), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.execute(request()))
                .isInstanceOf(SandboxExecutionUnavailableException.class)
                .hasMessageNotContaining(TOKEN);
        server.verify();
    }

    private static SandboxProperties properties() {
        SandboxProperties properties = new SandboxProperties();
        properties.setMode("http");
        properties.setBaseUrl("http://sandbox.test:9200");
        properties.setInternalToken(TOKEN);
        return properties;
    }

    private static SandboxExecutionRequest request() {
        return new SandboxExecutionRequest(
                "exec", "run", "call",
                "workspaces/00000000-0000-4000-8000-000000000003", "task",
                "coding-run-command", "mvn", List.of("test"), 30,
                new SandboxResourcePolicy(
                        65_536, 30, 268_435_456, false, List.of(".")), "{}");
    }
}
