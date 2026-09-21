package com.spaceagent.platform.observability;

import com.spaceagent.platform.observability.infrastructure.SupervisorShadowMetrics;
import com.spaceagent.platform.runtime.domain.SupervisorPolicyMode;
import com.spaceagent.platform.runtime.domain.SupervisorShadowComparison;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.when;

class SupervisorShadowMetricsTest {

    @Test
    void emitsOnlyBoundedOutcomeAndModeTagsAndCurrentSpanAttributes() {
        var registry = new SimpleMeterRegistry();
        var metrics = new SupervisorShadowMetrics(registry);
        var comparison = SupervisorShadowComparison.classify(
                "COMPLETED", "sha256:a", "HANDOFF", "sha256:a", false);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(span.getSpanContext()).thenReturn(SpanContext.getInvalid());

        try (var ignored = Context.current().with(span).makeCurrent()) {
            metrics.record(SupervisorPolicyMode.SHADOW, comparison);
        }

        assertThat(registry.get("spaceagent.supervisor.shadow.outcomes")
                .tag("mode", "SHADOW")
                .tag("outcome", "COMMAND_KIND_MISMATCH")
                .counter()
                .count()).isEqualTo(1);
        assertThat(metrics.traceAttributes(SupervisorPolicyMode.SHADOW, comparison))
                .containsOnlyKeys(
                        "spaceagent.supervisor.mode",
                        "spaceagent.supervisor.shadow.outcome");
        verify(span).setAttribute("spaceagent.supervisor.mode", "SHADOW");
        verify(span).setAttribute(
                "spaceagent.supervisor.shadow.outcome", "COMMAND_KIND_MISMATCH");
    }
}
