# ADR-048: Agent Operational Telemetry Uses OpenTelemetry and Prometheus

- Status: Accepted
- Date: 2026-09-06
- Scope: M52-PR1

## Context

SpaceAgent already has durable, owner-scoped business tracing derived from AgentRun, RunEvent,
ModelCallLedger, ToolExecutionLedger, Automation, collaboration and Artifact evidence. It also has
an optional Prometheus/Grafana/Loki/Tempo/Alloy Compose profile. The Java runtime, however, only
depends on Actuator: the declared Prometheus endpoint has no registry and Tempo has no Java OTLP
producer. Existing alerts cannot observe Agent outcomes or reach an alert management plane.

Replacing the durable Trace projection with a telemetry product would weaken recovery and UNKNOWN
semantics. Building a custom exporter or tracing protocol would duplicate mature ecosystem work.

## Decision

Use Spring Boot's managed Micrometer and OpenTelemetry integration:

- `micrometer-registry-prometheus` exposes JVM, HTTP, JDBC/pool and bounded Agent operational metrics;
- `micrometer-tracing-bridge-otel` plus `opentelemetry-exporter-otlp` provides W3C-correlated Spring
  traces and opt-in OTLP/HTTP export to Tempo or another operator-selected compatible collector;
- Loki collects application/container logs with trace correlation when tracing is enabled;
- Prometheus evaluates infrastructure and Agent SLO rules; Alertmanager groups, silences and routes
  them; Grafana remains the replaceable visualization surface.

Agent SLO metrics are refreshed from disposable Observability/Trace SQL views. They use a bounded
status/kind vocabulary and contain no tenant, user, Agent, Conversation, Task, Run or provider/model
identifier labels. Every replica may expose the same database projection, so dashboards and alerts
must use `max` across instances rather than `sum` unless they operate on instance-local JVM/HTTP data.

OTLP export is disabled by default and explicitly configured by the operator. Exported automatic
traces must not include prompts, model outputs, Tool arguments/results, secrets or business payloads.
The OpenTelemetry GenAI semantic conventions remain in Development; SpaceAgent will not freeze an
unstable custom attribute contract in this milestone.

## Authority and failure semantics

PostgreSQL Runtime/Ledgers and existing Trace views remain the only business evidence. Metrics,
spans, logs, dashboards and alerts are disposable operational projections. Their outage, delay,
duplication or sampling never changes execution, recovery, billing, approval or reconciliation.
Telemetry refresh failures preserve the last successful snapshot and expose stale/error health.

## Alternatives

- Langfuse and Arize Phoenix are mature open-source AI-observability products and may later consume
  OTLP as optional analysis/evaluation UIs. They are not embedded now because their data stores and
  product concepts would duplicate the current Trace UI and must not become authority.
- A Java agent provides broad zero-code instrumentation, but the managed Spring Boot dependencies
  give reproducible application packaging and focused configuration for the current monolith.
- A bespoke Agent telemetry service is rejected because OpenTelemetry, Micrometer, Prometheus,
  Tempo, Loki, Grafana and Alertmanager already provide the required infrastructure primitives.

## Consequences

The existing Observability business API is unchanged. Operators gain a working Prometheus scrape,
optional distributed tracing and bounded Agent SLO alerting without a new business database. A later
milestone may add trustworthy first-token timestamps and stabilized GenAI semantic attributes at the
Provider boundary.

## Upstream references

- [Spring Boot 3.5 tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html)
- [Spring Boot 3.5 metrics](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)
- [OpenTelemetry Java instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
- [OpenTelemetry GenAI semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai)
- [Prometheus Alertmanager](https://github.com/prometheus/alertmanager)
- [Langfuse](https://github.com/langfuse/langfuse)
- [Arize Phoenix](https://github.com/Arize-ai/phoenix)
