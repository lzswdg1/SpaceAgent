package com.spaceagent.platform.observability;

import com.spaceagent.platform.observability.domain.AgentOperationalMetricsQueryRepository;
import com.spaceagent.platform.observability.domain.AgentOperationalMetricsSnapshot;
import com.spaceagent.platform.observability.infrastructure.AgentOperationalMetricsBinder;
import com.spaceagent.platform.observability.infrastructure.AgentOperationalMetricsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOperationalMetricsBinderTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Test
    void exposesOnlyBoundedOutcomeAndEvidenceLabelsAndPreservesLastSnapshotOnFailure() {
        AtomicBoolean fail = new AtomicBoolean();
        AgentOperationalMetricsQueryRepository repository = (since, observedAt) -> {
            if (fail.get()) {
                throw new IllegalStateException("database detail must not become a metric label");
            }
            assertThat(since).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
            return snapshot(observedAt);
        };
        var registry = new SimpleMeterRegistry();
        var binder = new AgentOperationalMetricsBinder(repository, () -> NOW, properties());
        binder.bindTo(registry);

        binder.refreshNow();

        assertThat(gauge(registry, "spaceagent.agent.runs.active")).isEqualTo(2);
        assertThat(gauge(registry, "spaceagent.agent.runs.window", "outcome", "success"))
                .isEqualTo(7);
        assertThat(gauge(registry, "spaceagent.agent.model.calls.window", "outcome", "unknown"))
                .isEqualTo(1);
        assertThat(gauge(registry, "spaceagent.agent.tool.executions.window", "outcome", "error"))
                .isEqualTo(2);
        assertThat(gauge(registry, "spaceagent.agent.latency.p95", "operation", "model_call"))
                .isEqualTo(1.2);
        assertThat(gauge(registry, "spaceagent.agent.latency.p95", "operation", "model_first_chunk"))
                .isEqualTo(0.45);
        assertThat(gauge(registry, "spaceagent.agent.tokens.window", "type", "cache_read"))
                .isEqualTo(30);
        assertThat(gauge(registry, "spaceagent.agent.evidence.window", "kind", "handoff_error"))
                .isEqualTo(1);
        assertThat(gauge(registry, "spaceagent.agent.telemetry.refresh.success")).isEqualTo(1);

        fail.set(true);
        binder.refreshNow();

        assertThat(gauge(registry, "spaceagent.agent.telemetry.refresh.success")).isZero();
        assertThat(gauge(registry, "spaceagent.agent.runs.window", "outcome", "success"))
                .isEqualTo(7);
        assertThat(registry.get("spaceagent.agent.telemetry.refresh.failures")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .distinct())
                .containsExactlyInAnyOrder("outcome", "operation", "type", "kind");

        fail.set(false);
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var prometheusBinder = new AgentOperationalMetricsBinder(
                repository, () -> NOW, properties());
        prometheusBinder.bindTo(prometheus);
        prometheusBinder.refreshNow();
        assertThat(prometheus.scrape())
                .contains("spaceagent_agent_runs_active")
                .contains("spaceagent_agent_latency_p95_seconds")
                .contains("spaceagent_agent_model_calls_window")
                .contains("spaceagent_agent_tool_executions_window")
                .contains("spaceagent_agent_telemetry_refresh_age_seconds")
                .contains("spaceagent_agent_telemetry_window_seconds")
                .doesNotContain("tenant", "user", "conversation", "provider_id", "agent_id");
    }

    private static AgentOperationalMetricsProperties properties() {
        var properties = new AgentOperationalMetricsProperties();
        properties.setWindow(Duration.ofMinutes(5));
        return properties;
    }

    private static AgentOperationalMetricsSnapshot snapshot(Instant observedAt) {
        return new AgentOperationalMetricsSnapshot(
                observedAt,
                2,
                1,
                new AgentOperationalMetricsSnapshot.OutcomeCounts(10, 7, 1, 1, 0, 1),
                new AgentOperationalMetricsSnapshot.OutcomeCounts(12, 9, 1, 0, 1, 1),
                new AgentOperationalMetricsSnapshot.OutcomeCounts(8, 5, 2, 0, 0, 1),
                new AgentOperationalMetricsSnapshot.LatencyP95(2500, 1200, 300, 450),
                new AgentOperationalMetricsSnapshot.TokenUsage(100, 50, 30, 20),
                new AgentOperationalMetricsSnapshot.CostEvidence(2_500_000, 1),
                new AgentOperationalMetricsSnapshot.CollaborationEvidence(3, 1, 2, 1, 4, 1));
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private static double gauge(
            SimpleMeterRegistry registry,
            String name,
            String tag,
            String value) {
        return registry.get(name).tag(tag, value).gauge().value();
    }
}
