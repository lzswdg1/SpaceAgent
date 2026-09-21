package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.InferenceTelemetry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MicrometerInferenceTelemetry implements InferenceTelemetry {
    public static final String SEMCONV_COMMIT =
            "94f432d7126f5884d30a2cdde6f4e89908ebb6fd";
    private final Tracer tracer;

    public MicrometerInferenceTelemetry(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public CallSpan start(String model, boolean streaming) {
        String safeModel = safe(model, "unknown", 120);
        Span span = tracer.spanBuilder().name("chat " + safeModel)
                .kind(Span.Kind.CLIENT)
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.provider.name", "openai_compatible")
                .tag("gen_ai.request.model", safeModel)
                .tag("gen_ai.request.stream", Boolean.toString(streaming))
                .tag("spaceagent.gen_ai.semconv.commit", SEMCONV_COMMIT)
                .start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return new CallSpan() {
            @Override
            public void firstChunk(long milliseconds) {
                span.tag("gen_ai.response.time_to_first_chunk",
                        Double.toString(Math.max(0, milliseconds) / 1000D));
            }

            @Override
            public void success(int inputTokens, int outputTokens) {
                span.tag("gen_ai.usage.input_tokens", Integer.toString(Math.max(0, inputTokens)));
                span.tag("gen_ai.usage.output_tokens", Integer.toString(Math.max(0, outputTokens)));
            }

            @Override
            public void error(String errorType) {
                span.tag("error.type", safe(errorType, "_OTHER", 120));
                span.error(new IllegalStateException("redacted GenAI operation failure"));
            }

            @Override
            public void close() {
                scope.close();
                span.end();
            }
        };
    }

    private static String safe(String value, String fallback, int maximum) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && normalized.length() <= maximum
                && normalized.matches("[a-z0-9._:/-]+") ? normalized : fallback;
    }
}
