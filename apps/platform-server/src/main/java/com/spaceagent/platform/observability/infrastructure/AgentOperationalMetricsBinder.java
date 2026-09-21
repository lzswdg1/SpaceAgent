package com.spaceagent.platform.observability.infrastructure;

import com.spaceagent.platform.observability.domain.AgentOperationalMetricsQueryRepository;
import com.spaceagent.platform.observability.domain.AgentOperationalMetricsSnapshot;
import com.spaceagent.shared.time.TimeProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Exposes database-backed Agent SLO gauges without turning process counters into business truth.
 * Every label vocabulary is finite and intentionally excludes tenant/resource identifiers.
 */
@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@ConditionalOnProperty(
        prefix = "platform.observability.operational-metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentOperationalMetricsBinder implements MeterBinder {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AgentOperationalMetricsBinder.class);
    private static final List<String> OUTCOMES =
            List.of("total", "success", "error", "aborted", "unknown", "running");
    private static final List<String> OPERATIONS =
            List.of("agent_run", "model_call", "tool_execution", "model_first_chunk");
    private static final List<String> TOKEN_TYPES =
            List.of("input", "output", "cache_read", "cache_create");
    private static final List<String> EVIDENCE_KINDS = List.of(
            "handoff", "handoff_error", "review", "review_changes_requested",
            "acceptance_evidence", "continuation_failure");

    private final AgentOperationalMetricsQueryRepository repository;
    private final TimeProvider time;
    private final Duration window;
    private final AtomicReference<State> state;
    private final AtomicReference<Counter> refreshFailures = new AtomicReference<>();

    public AgentOperationalMetricsBinder(
            AgentOperationalMetricsQueryRepository repository,
            TimeProvider time,
            AgentOperationalMetricsProperties properties) {
        this.repository = repository;
        this.time = time;
        this.window = properties.getWindow();
        Instant initial = Instant.EPOCH;
        this.state = new AtomicReference<>(new State(
                AgentOperationalMetricsSnapshot.empty(initial), false, initial));
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("spaceagent.agent.runs.active", state,
                        value -> value.get().snapshot().activeRuns())
                .description("Current non-terminal Agent runs from the durable Trace projection")
                .register(registry);
        Gauge.builder("spaceagent.agent.runs.recovering", state,
                        value -> value.get().snapshot().recoveringRuns())
                .description("Current Agent runs in RECOVERING state")
                .register(registry);

        bindOutcomes(registry, "spaceagent.agent.runs.window",
                AgentOperationalMetricsSnapshot::runs,
                "Agent run outcomes in the configured rolling window");
        bindOutcomes(registry, "spaceagent.agent.model.calls.window",
                AgentOperationalMetricsSnapshot::modelCalls,
                "Model call outcomes in the configured rolling window");
        bindOutcomes(registry, "spaceagent.agent.tool.executions.window",
                AgentOperationalMetricsSnapshot::toolExecutions,
                "Tool execution outcomes in the configured rolling window");

        for (String operation : OPERATIONS) {
            Gauge.builder("spaceagent.agent.latency.p95", state,
                            value -> value.get().snapshot().latencyP95().millis(operation) / 1000D)
                    .tag("operation", operation)
                    .baseUnit("seconds")
                    .description("P95 completed operation latency in the rolling window")
                    .register(registry);
        }
        for (String tokenType : TOKEN_TYPES) {
            Gauge.builder("spaceagent.agent.tokens.window", state,
                            value -> value.get().snapshot().tokens().value(tokenType))
                    .tag("type", tokenType)
                    .description("Model token evidence in the configured rolling window")
                    .register(registry);
        }
        Gauge.builder("spaceagent.agent.cost.settled.usd", state,
                        value -> value.get().snapshot().cost().settledMicros() / 1_000_000D)
                .description("Settled USD cost with complete immutable pricing evidence")
                .register(registry);
        Gauge.builder("spaceagent.agent.cost.incomplete.runs.window", state,
                        value -> value.get().snapshot().cost().incompleteRuns())
                .description("Runs with model calls but incomplete pricing evidence")
                .register(registry);

        for (String kind : EVIDENCE_KINDS) {
            Gauge.builder("spaceagent.agent.evidence.window", state,
                            value -> value.get().snapshot().collaboration().value(kind))
                    .tag("kind", kind)
                    .description("Collaboration, recovery and acceptance evidence in the window")
                    .register(registry);
        }

        Gauge.builder("spaceagent.agent.telemetry.refresh.success", state,
                        value -> value.get().lastAttemptSuccessful() ? 1D : 0D)
                .description("Whether the last Agent operational metric refresh succeeded")
                .register(registry);
        Gauge.builder("spaceagent.agent.telemetry.refresh.age", state, value -> {
                    Instant lastSuccess = value.get().lastSuccessAt();
                    if (Instant.EPOCH.equals(lastSuccess)) {
                        return Double.POSITIVE_INFINITY;
                    }
                    return Math.max(0, Duration.between(lastSuccess, time.now()).toSeconds());
                })
                .baseUnit("seconds")
                .description("Seconds since the last successful Agent metric refresh")
                .register(registry);
        Gauge.builder("spaceagent.agent.telemetry.window", window, Duration::toSeconds)
                .baseUnit("seconds")
                .description("Rolling database projection window used by Agent operational metrics")
                .register(registry);
        refreshFailures.set(Counter.builder("spaceagent.agent.telemetry.refresh.failures")
                .description("Process-local Agent operational metric refresh failures")
                .register(registry));
    }

    @Scheduled(
            initialDelayString =
                    "${platform.observability.operational-metrics.refresh-delay-ms:15000}",
            fixedDelayString =
                    "${platform.observability.operational-metrics.refresh-delay-ms:15000}")
    public void refreshNow() {
        Instant attemptedAt = time.now();
        try {
            AgentOperationalMetricsSnapshot snapshot =
                    repository.snapshot(attemptedAt.minus(window), attemptedAt);
            state.set(new State(snapshot, true, attemptedAt));
        } catch (RuntimeException error) {
            State previous = state.get();
            state.set(new State(
                    previous.snapshot(), false, previous.lastSuccessAt()));
            Counter failures = refreshFailures.get();
            if (failures != null) {
                failures.increment();
            }
            LOGGER.warn("Agent operational metric refresh failed: {}",
                    error.getClass().getSimpleName());
        }
    }

    private void bindOutcomes(
            MeterRegistry registry,
            String name,
            Function<AgentOperationalMetricsSnapshot,
                    AgentOperationalMetricsSnapshot.OutcomeCounts> selector,
            String description) {
        for (String outcome : OUTCOMES) {
            Gauge.builder(name, state,
                            value -> selector.apply(value.get().snapshot()).value(outcome))
                    .tag("outcome", outcome)
                    .description(description)
                    .register(registry);
        }
    }

    private record State(
            AgentOperationalMetricsSnapshot snapshot,
            boolean lastAttemptSuccessful,
            Instant lastSuccessAt) {}
}
