# ADR-009: Provider Connection Test and ModelPool Foundation

- Status: Accepted
- Date: 2026-08-22
- Scope: M14-PR1

## Context

The active Inference module already owns encrypted Provider credentials, endpoint
allowlisting, configured Provider models, and real OpenAI-compatible execution. It has
no durable connection-test state and Agents still reference one provider/model directly.
The target product requires a tested Provider to become eligible for one or more stable
ModelPools shared by multiple Agents.

## Decision

- Keep Provider secrets, endpoint policy, HTTP testing, ModelPool, routing, and usage in
  Java Inference. TypeScript orchestration receives pool references, never secrets.
- Add Provider connection states `UNTESTED`, `ACTIVE`, `UNHEALTHY`, and `DISABLED`, plus
  last-tested time, latency, and safe error code.
- A connection test calls the allowlisted OpenAI-compatible `GET /models` endpoint using
  the configured auth mode and decrypted secret only inside Inference infrastructure.
- A test returns a structured success/failure result. Failures update Provider health but
  never expose URL credentials, response bodies, or exception messages.
- Changing Base URL, Provider type, auth type, or secret resets the Provider to UNTESTED;
  disabling sets DISABLED.
- Existing direct Agent provider/model references remain compatible in M14-PR1. Only new
  ModelPool membership requires an enabled, successfully tested ACTIVE Provider.
- Do not auto-import discovered models. Connection test returns discovered IDs; an
  administrator explicitly configures ProviderModel metadata/context limits before pool
  membership.

## ModelPool

ModelPool is a durable Inference aggregate:

- tenant/Organization and owner;
- visibility `PRIVATE` or `ORGANIZATION`;
- routing strategy `PRIORITY` in the first implementation;
- fallback enabled/disabled;
- lifecycle `DRAFT`, `ACTIVE`, `DISABLED`;
- ordered members referencing a configured ProviderModel;
- member priority, weight metadata, and enabled state.

Activation requires at least one enabled member whose Provider is enabled and ACTIVE.
Resolution returns deterministic candidates ordered by priority, then stable member ID;
when fallback is disabled it returns only the first candidate. Weighted/adaptive routing,
cost policy, health probes, quotas, and Agent binding are later increments.

## Authorization

- Provider management/testing keeps the existing Organization ADMIN boundary.
- Any Organization write-capable user may create a pool they own.
- Pool mutation requires the pool owner in M14-PR1.
- PRIVATE pools are visible/resolvable only to their owner; ORGANIZATION pools are visible
  to authenticated users in the same active Organization.
- Java Application APIs receive explicit tenant/user identity; HTTP remains an adapter.

## Persistence and compatibility

- V1010 alters `platform_model_providers` with test state and adds
  `platform_model_pools` plus `platform_model_pool_members`.
- Pool-local IDs use UUID; existing Provider/ProviderModel/Tenant/User IDs remain their
  current `VARCHAR(36)` compatibility type.
- Provider deletion is rejected while a pool member references it.
- No AgentDefinition/AgentVersion schema or Runtime flow changes in this milestone.

## Consequences

- Secret-safe connection health becomes durable and auditable.
- Multiple pools may reuse one tested ProviderModel without copying credentials.
- Later Agent versions can migrate from a direct provider/model pair to `modelPoolId`
  behind a separate compatibility objective.
