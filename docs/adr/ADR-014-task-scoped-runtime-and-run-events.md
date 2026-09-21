# ADR-014: Task-scoped Runtime and RunEvent Authority

> M38-PR2 follow-up: the historical Python orchestration compatibility path described
> below has been removed; the TypeScript `multi-agent/v1` boundary remains active.

- Status: Accepted
- Date: 2026-08-22
- Scope: M18-PR1

## Context

M16 introduced Java-owned TaskPlan/PlanStep state and M17 froze a compute-only
TypeScript `multi-agent/v1` contract. Runtime still stores Project and Task references
as compatibility strings, reconstructs its cursor indirectly from checkpoint JSON, and
has no independently queryable append-only execution event stream.

## Decision

- Java Runtime validates tenant/user/Project/Task/TaskPlan/PlanStep binding through a
  Project public Application API. Runtime never imports Project repositories.
- A Project run pins the canonical Project UUID, child Task UUID, active TaskPlan UUID,
  and matching PlanStep UUID. Chat-only runs may omit all four references.
- `AgentRun` persists a typed execution cursor and monotonic revision. Runtime appends a
  monotonic `RunEvent` for accepted lifecycle, step, checkpoint, cursor, and external
  orchestration-command boundaries.
- PostgreSQL remains authoritative. M18 adds UUID columns and foreign keys additively,
  backfills only valid historical references, and retains legacy string columns for
  compatibility until a separately reviewed cleanup migration.
- The TypeScript HTTP adapter is selected only when
  `platform.multi-agent-orchestrator.mode=http`. The default remains `none`; Python
  compatibility routing is unchanged and there is no production cutover in M18.
- TypeScript responses are proposals. Java validates and records the accepted command;
  TypeScript cannot persist state or execute Model, Tool, Git, MCP, or filesystem effects.

## Validation boundary

M18 covers domain/application tests, mocked HTTP contract tests, PostgreSQL/Testcontainers
migration and repository tests, full Maven regression, package, and architecture checks.
At the user's direction this batch does not start real Java and TypeScript processes for
cross-process end-to-end testing. Shadow/recovery parity and production routing remain a
later gate.

## Consequences

- Runtime can resume and audit a Task-scoped execution without relying on process memory
  or a LangGraph checkpointer.
- Project remains the owner of TaskPlan structure while Runtime owns execution history.
- Existing Chat and Python orchestration paths remain source-compatible during the
  additive migration.
