# ADR-010: Agent-to-ModelPool Compatibility Binding

- Status: Accepted
- Date: 2026-08-22
- Scope: M15-PR1

## Context

M14 introduced durable, tested ModelPools, while every AgentVersion still stores a direct
Provider/Model pair. The target architecture requires the stable Agent configuration to
reference a ModelPool and Java Inference to choose the actual Provider/Model without
exposing secrets. Existing clients and persisted AgentVersions must keep working during
the transition.

## Decision

- Add nullable `modelPoolId` to the immutable AgentVersion snapshot and all safe public
  projections. It is included in the configuration hash only when non-null so historical
  direct-version hashes remain stable.
- A pool binding is mutually exclusive with `modelProviderId` + `modelId`. Existing direct
  bindings and empty bindings remain compatible.
- Agent Application validates a new pool reference only through
  `ModelPoolApplicationApi.resolvePool(tenantId, ownerId, poolId)`. This proves tenant,
  visibility, ACTIVE status, and at least one eligible candidate without importing
  Inference repositories.
- Chat Runtime resolves a pool again when starting a new run, chooses the first candidate
  from the deterministic Priority resolution, and writes `modelPoolId`, Provider, Model,
  and candidate count into a durable checkpoint before inference.
- A request-level model override is rejected for pool-bound Agents because it would bypass
  the pool policy.

## Compatibility

- V1011 adds one nullable UUID foreign key to `platform_agent_versions`; no existing row
  is rewritten.
- Direct Provider/Model Agent creation, updates, HTTP responses, and Runtime execution
  remain supported.
- Published AgentVersion rows stay immutable and AgentRun continues pinning a version ID.

## Deferred

- Automatic fallback execution is not added in this batch. A failure may be ambiguous
  (`UNKNOWN`) and retrying another Provider could duplicate generation or billing.
- Per-run model decision columns/ledger, scheduled health probes, weighted/cost/latency
  routing, quotas, and advanced constraints are later Inference/Runtime increments.
- Explicit AgentVersion Draft/Review/Publish/Deprecate/Rollback is M15-PR2.
