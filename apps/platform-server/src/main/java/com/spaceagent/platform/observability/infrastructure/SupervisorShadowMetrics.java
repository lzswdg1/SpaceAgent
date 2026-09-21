package com.spaceagent.platform.observability.infrastructure;

import com.spaceagent.platform.runtime.domain.SupervisorPolicyMode;
import com.spaceagent.platform.runtime.domain.SupervisorShadowComparison;
import com.spaceagent.platform.runtime.domain.SupervisorShadowTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Process-local low-cardinality observability only; it never contains run, tenant, prompt or response data. */
@Component
public class SupervisorShadowMetrics implements SupervisorShadowTelemetry {

    private final MeterRegistry registry;

    public SupervisorShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(
            SupervisorPolicyMode mode,
            SupervisorShadowComparison comparison) {
        Map<String, String> attributes = traceAttributes(mode, comparison);
        Counter.builder("spaceagent.supervisor.shadow.outcomes")
                .tag("mode", attributes.get("spaceagent.supervisor.mode"))
                .tag("outcome", attributes.get("spaceagent.supervisor.shadow.outcome"))
                .register(registry)
                .increment();
        Span.current().setAttribute(
                "spaceagent.supervisor.mode",
                attributes.get("spaceagent.supervisor.mode"));
        Span.current().setAttribute(
                "spaceagent.supervisor.shadow.outcome",
                attributes.get("spaceagent.supervisor.shadow.outcome"));
    }

    public Map<String, String> traceAttributes(
            SupervisorPolicyMode mode,
            SupervisorShadowComparison comparison) {
        return Map.of(
                "spaceagent.supervisor.mode", mode.name(),
                "spaceagent.supervisor.shadow.outcome", comparison.outcome().name());
    }
}
