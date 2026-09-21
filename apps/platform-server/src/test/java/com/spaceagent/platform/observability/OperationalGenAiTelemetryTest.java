package com.spaceagent.platform.observability;

import com.spaceagent.platform.inference.infrastructure.MicrometerInferenceTelemetry;
import com.spaceagent.platform.runtime.infrastructure.MicrometerRuntimeOperationalTelemetry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalGenAiTelemetryTest {
    @Test
    void emitsPinnedRedactedInferenceAndToolAttributes() {
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.kind(org.mockito.ArgumentMatchers.any())).thenReturn(builder);
        when(builder.tag(anyString(), anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);

        var inference = new MicrometerInferenceTelemetry(tracer);
        try (var call = inference.start("model-safe", true)) {
            call.firstChunk(125);
            call.success(7, 3);
        }
        verify(builder).kind(Span.Kind.CLIENT);
        verify(builder).tag("gen_ai.operation.name", "chat");
        verify(builder).tag("gen_ai.provider.name", "openai_compatible");
        verify(builder).tag("gen_ai.request.stream", "true");
        verify(span).tag("gen_ai.response.time_to_first_chunk", "0.125");
        verify(builder).tag("spaceagent.gen_ai.semconv.commit",
                MicrometerInferenceTelemetry.SEMCONV_COMMIT);

        var runtime = new MicrometerRuntimeOperationalTelemetry(tracer);
        try (var tool = runtime.startTool("coding_write_file")) {
            tool.success();
        }
        verify(builder).tag("gen_ai.operation.name", "execute_tool");
        verify(span).tag("gen_ai.tool.name", "coding_write_file");
        verify(builder, never()).tag(org.mockito.ArgumentMatchers.matches(
                ".*(prompt|reasoning|arguments|result|tenant|conversation|run|task).*"),
                anyString());
        verify(span, never()).tag(org.mockito.ArgumentMatchers.matches(
                ".*(prompt|reasoning|arguments|result|tenant|conversation|run|task).*"),
                anyString());
    }
}
