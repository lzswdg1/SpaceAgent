# ADR-079: Mutable Agent Current Configuration and Per-Run Snapshot

- Status: Accepted
- Date: 2026-09-11
- Scope: M74 Agent configuration lifecycle simplification
- Supersedes: ADR-003 AgentVersion as the product/runtime configuration authority;
  ADR-073 AgentVersion governance; ADR-077 AgentVersion activation scheduling
- Partially supersedes: ADR-060/070/078 wherever Skill, MCP or Project assignment is keyed by
  `agentVersionId`

## Context

SpaceAgent currently exposes an enterprise release workflow for every Agent configuration change:

```text
AgentDefinition -> AgentVersion DRAFT -> review -> publication approval -> PUBLISHED -> current pointer
```

Runtime, Project, Automation, Tool/Skill/MCP binding and Multi-Agent flows then pin the published
AgentVersion. This provides reproducible Runs, but it also makes a newly created Agent unusable until
an independent reviewer and approver complete a multi-step workflow. The production-default
separation policy makes a one-member Personal Organization unable to publish its own first Agent.

The product owner has explicitly removed Agent version management from the product: creating an
Agent must make it usable immediately, and saving an Agent must update that Agent immediately.
There must be no Draft/Review/Publish/Deprecate/Rollback/activation-schedule lifecycle and no
user-visible or authoritative `AgentVersion` history.

Removing reproducibility altogether would make an in-flight Run change behavior after every Agent
save and would make restart/handoff recovery nondeterministic. That is not required by the product
decision and would violate Runtime recovery guarantees. Reproducibility therefore moves from an
Agent release entity to a Runtime-owned, immutable per-Run configuration snapshot.

## Decision

### Agent authority

Java Agent/PostgreSQL owns one mutable current configuration per active `AgentDefinition`.

```text
AgentDefinition
  -> AgentCurrentConfiguration (one-to-one, revision + configHash)
```

- Create validates all references and atomically inserts Definition plus current configuration.
- Update locks the Agent, validates the complete replacement configuration and atomically updates
  the current row with revision CAS semantics.
- Name/description and runtime configuration are saved through the same public Agent write use case.
- Create and update are immediately available to later Chat/Project/Automation Runs.
- Agent delete remains a soft archive. It disables future use but preserves historical Run evidence.
- There is no Agent draft, publish, deprecate, rollback, review or scheduled activation state.

Direct Agent bindings for Tool, immutable SkillVersion and qualified MCP capability references are
part of the current configuration. Updating them changes only later Runs.

### Runtime reproducibility

Runtime owns an immutable `AgentRunConfigurationSnapshot` created exactly when a Run is admitted.
The snapshot contains the effective, secret-free current Agent configuration, exact Tool/Skill/MCP
bindings, Agent revision and configuration hash. It is keyed by the Run and cannot be reused as an
Agent release/version entity.

```text
resolve current Agent configuration
  -> validate tenant/owner/status/references
  -> start AgentRun
  -> persist one immutable Run configuration snapshot
  -> execute/recover/handoff from that snapshot
```

Provider and MCP secrets remain in their owner modules and are resolved at call time. The snapshot
stores only authorized references and hashes. A later Agent save does not mutate an existing Run,
Checkpoint, Tool approval, Tool effect, Artifact or recovery package.

Queued Project and Automation work stores `agentId` (and reviewer `agentId` where required), not an
Agent configuration release ID. The current configuration is resolved when a new Run starts; after
that point the Run snapshot is authoritative. Project assignments retain immutable assignment
evidence for Agent identity and role, while Runtime owns effective configuration evidence.

### Schema transition

Applied Flyway migrations remain immutable. Forward migrations use expand/migrate/contract:

1. add/backfill current Agent configuration and per-Run snapshot storage;
2. migrate Agent, Chat, Project, Runtime, Automation, Multi-Agent and Tooling consumers;
3. migrate exact Skill/MCP bindings to current Agent configuration;
4. remove AgentVersion public APIs, workers, governance projections and Java domain/persistence;
5. drop AgentVersion review/schedule/binding/version tables and obsolete foreign keys only after
   every historical Run has a snapshot or an explicit `LEGACY_UNSNAPSHOTTED` marker.

Historical published configuration needed by an existing Run is copied into that Run's snapshot.
Unreferenced Draft/Review/Version history is intentionally discarded at contract migration.

## Failure and concurrency semantics

- Create/update is a single Agent-owned transaction; validation failure leaves the previous current
  configuration unchanged.
- Concurrent save uses Agent/config revision CAS. A stale writer receives a stable conflict and must
  reload; last-writer-wins is not allowed silently.
- Run admission fails closed if current configuration is missing, invalid, archived or changed
  between validation and snapshot persistence.
- Existing Runs never re-resolve the mutable Agent after admission.
- Tool/Provider `UNKNOWN`, Governance approval, Ledger replay/fencing and Workspace isolation remain
  unchanged.

## Compatibility boundary

- During the expand/migrate phase, compatibility readers may project legacy AgentVersion rows, but
  no milestone may claim the new lifecycle complete while create/update still produces versions.
- Final public Agent CRUD contains no version-management endpoints or `currentAgentVersionId`.
- Frontend migration is outside the backend-only M74 scope unless separately authorized. Existing
  version UI may fail after the final contract cutover and must not keep obsolete backend authority.
- Admin receives current Agent metadata and Run-snapshot evidence only; it never receives prompts,
  Tool payloads, Provider/MCP secrets or a replacement version-governance control plane.

## Consequences

- Personal users can create an Agent and use it immediately.
- Saving configuration becomes one comprehensible operation.
- Enterprise governance still protects dangerous Tool effects and Organization policy, but no
  longer governs ordinary Agent configuration publication.
- Run storage increases because effective configuration is copied once per Run.
- Agent history/rollback is removed by design. A user who wants an older configuration must edit and
  save it as the current configuration.
- The migration is intentionally multi-unit because more than one hundred Java files and multiple
  durable foreign-key consumers currently reference AgentVersion.
