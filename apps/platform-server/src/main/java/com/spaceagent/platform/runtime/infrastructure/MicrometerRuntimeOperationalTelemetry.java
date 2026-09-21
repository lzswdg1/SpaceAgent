package com.spaceagent.platform.runtime.infrastructure;

import com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MicrometerRuntimeOperationalTelemetry implements RuntimeOperationalTelemetry {
    private static final String SEMCONV_COMMIT =
            "94f432d7126f5884d30a2cdde6f4e89908ebb6fd";
    private final Tracer tracer;

    public MicrometerRuntimeOperationalTelemetry(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public InvocationSpan startAgent(String version, String workflow) {
        return start("invoke_agent", "invoke_agent", span -> {
            span.tag("gen_ai.agent.config.revision", safe(version, "unknown", 40));
            span.tag("gen_ai.workflow.name", safe(workflow, "spaceagent", 80));
        });
    }

    @Override
    public InvocationSpan startTool(String toolName) {
        String tool = safe(toolName, "unknown", 120);
        return start("execute_tool " + tool, "execute_tool", span -> {
            span.tag("gen_ai.tool.name", tool);
            span.tag("gen_ai.tool.type", "function");
        });
    }

    private InvocationSpan start(
            String name, String operation, java.util.function.Consumer<Span> attributes) {
        Span span = tracer.spanBuilder().name(name)
                .tag("gen_ai.operation.name", operation)
                .tag("spaceagent.gen_ai.semconv.commit", SEMCONV_COMMIT)
                .start();
        attributes.accept(span);
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return new InvocationSpan() {
            public void success() { span.tag("spaceagent.outcome", "success"); }
            public void error(String errorType) {
                span.tag("error.type", safe(errorType, "_OTHER", 120));
                span.error(new IllegalStateException("redacted Agent operation failure"));
            }
            public void close() { scope.close(); span.end(); }
        };
    }

    private static String safe(String value, String fallback, int maximum) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && normalized.length() <= maximum
                && normalized.matches("[a-z0-9._:/-]+") ? normalized : fallback;
    }
}
