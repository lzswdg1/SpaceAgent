package com.spaceagent.platform.inference.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleInferenceExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerRejectionEvidenceIsBoundedAndSecretSafe() {
        String summary = OpenAiCompatibleInferenceExecutor.safeProviderRejectionSummary(
                objectMapper,
                400,
                """
                {"error":{"code":"invalid_parameter_error","message":"temperature must be 1; api_key=sk-live-secret-value"}}
                """);

        assertThat(summary)
                .contains("HTTP 400", "invalid_parameter_error", "temperature must be 1")
                .contains("api_key=[REDACTED]")
                .doesNotContain("sk-live-secret-value")
                .hasSizeLessThanOrEqualTo(360);
    }

    @Test
    void malformedProviderBodiesRemainGeneric() {
        assertThat(OpenAiCompatibleInferenceExecutor.safeProviderRejectionSummary(
                objectMapper, 400, "not-json sk-live-secret-value"))
                .isEqualTo("Inference provider returned HTTP 400");
    }

    @Test
    void recognizesNestedProviderReadTimeoutsWithoutMatchingGenericTransportFailures() {
        RuntimeException timeout = new RuntimeException(
                "provider transport", new SocketTimeoutException("read timed out"));
        assertThat(OpenAiCompatibleInferenceExecutor.isTimeoutFailure(timeout)).isTrue();
        assertThat(OpenAiCompatibleInferenceExecutor.isTimeoutFailure(
                new RuntimeException("connection reset"))).isFalse();
    }

    @Test
    void parsesReasoningContentAndIncrementalToolCallsFromProviderSse() {
        String stream = """
                data: {"id":"req-1","choices":[{"delta":{"reasoning_content":"check "}}]}

                data: {"choices":[{"delta":{"content":"hello ","tool_calls":[{"index":0,"id":"call-1","function":{"name":"web_search","arguments":"{\\\"query\\\":"}}]}}]}

                data: {"choices":[{"delta":{"content":"world","tool_calls":[{"function":{"arguments":"\\\"SpaceAgent\\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":8}}

                data: [DONE]

                """;
        StringBuilder reasoning = new StringBuilder();
        StringBuilder content = new StringBuilder();
        AtomicInteger firstChunks = new AtomicInteger();
        OpenAiCompatibleInferenceExecutor executor = new OpenAiCompatibleInferenceExecutor(
                null, null, objectMapper, new InferenceProperties());
        var result = executor.parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                new com.spaceagent.platform.inference.domain.InferenceExecutor.StreamObserver() {
                    public void onFirstChunk() { firstChunks.incrementAndGet(); }
                    public void onReasoningDelta(String value) { reasoning.append(value); }
                    public void onContentDelta(String value) { content.append(value); }
                });

        assertThat(reasoning.toString()).isEqualTo("check ");
        assertThat(content.toString()).isEqualTo("hello world");
        assertThat(firstChunks).hasValue(1);
        assertThat(result.reasoningContent()).isEqualTo("check ");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("web_search");
            assertThat(call.arguments()).isEqualTo("{\"query\":\"SpaceAgent\"}");
        });
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(8);
    }

    @Test
    void createsStableStreamLocalToolIdAndAcceptsObjectArguments() {
        String stream = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_search","arguments":{"query":"SpaceAgent"}}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        OpenAiCompatibleInferenceExecutor executor = new OpenAiCompatibleInferenceExecutor(
                null, null, objectMapper, new InferenceProperties());
        AtomicInteger firstChunks = new AtomicInteger();
        var result = executor.parseStream(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                new com.spaceagent.platform.inference.domain.InferenceExecutor.StreamObserver() {
                    public void onFirstChunk() { firstChunks.incrementAndGet(); }
                    public void onReasoningDelta(String value) { }
                    public void onContentDelta(String value) { }
                });
        assertThat(firstChunks).hasValue(1);
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("stream-call-0");
            assertThat(call.arguments()).isEqualTo("{\"query\":\"SpaceAgent\"}");
        });
    }
}
