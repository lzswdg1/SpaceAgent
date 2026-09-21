# ADR-023: Disposable Trace read model over authoritative evidence

- Status: Accepted
- Date: 2026-08-23
- Milestone: M24-PR4
- Scope: Backend only

## Context

The repository had a Tracing client contract but no active Java endpoints. Runtime,
ModelCallLedger, ToolExecutionLedger, Automation, Handoff/Delegation/Review and Artifact
already persist the evidence needed for a trace. Adding a separate writable span store or
using Redis/telemetry export as recovery truth would duplicate authority and permit drift.

## Decision

Observability owns a read-only Trace projection. V1024 creates rebuildable
`platform_trace_roots`, `platform_trace_spans`, and `platform_trace_summaries` views.
AgentRun is the root; ModelCall and Tool ledgers are LLM/Tool spans; RunEvent,
AutomationExecution, Handoff, Delegation, Review and Artifact records are redacted system
evidence spans. Source modules continue to own every durable row.

Java exposes owner-scoped list/detail/stats endpoints under `/api/v1/users/tracing`.
Every query requires ACTIVE membership and filters both tenant and authenticated owner.
Legacy non-UUID Run/evidence IDs are adapted to deterministic UUIDs at the read boundary.

The projection excludes prompts, messages, raw checkpoint snapshots, model response
payloads, tool arguments/results/result references, review evidence, Artifact content
references/summaries/metadata, secrets and claim tokens. Only bounded errors, IDs, hashes,
states, timing and token counts are exposed.

Cost remains null until pricing is versioned. Per-trace first-token latency remains null
because the Provider boundary does not persist a trustworthy first-token timestamp; the
aggregate compatibility field uses zero as an explicit unavailable sentinel.

## Consequences

- Dropping/rebuilding Trace views cannot affect execution or recovery.
- Trace endpoints never mutate source state.
- Telemetry exporters may consume the projection later but cannot become authority.
- Backend matches the existing client wire contract; this milestone intentionally makes no
  frontend source change or browser E2E claim.
