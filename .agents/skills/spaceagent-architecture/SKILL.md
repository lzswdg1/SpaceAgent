---
name: spaceagent-architecture
description: Design, audit, or refactor SpaceAgent business ownership, lifecycles, persistence, runtime flows, and language boundaries with risk-proportional planning. Do not use for routine localized fixes.
---

# SpaceAgent Architecture

Use this Skill only when a change moves authority, changes a durable lifecycle or failure guarantee,
crosses owner or runtime boundaries, or needs a recoverable migration. A large repository or diff is
not by itself an architecture task.

## Proportional discovery

Always inspect:

- `AGENTS.md` and current Git status;
- the affected source, public contract, persistence mapping, and tests;
- the relevant business owner and end-to-end runtime flow.

Read only the header and relevant active section of `.agent/CURRENT.md` or
`.agent/BACKEND-PLAN.md`, plus the architecture document or ADR that governs the affected boundary.
Do not read all of `.agent/V2-REFACTOR-PLAN.md` or thousands of completed log lines unless the task
specifically asks about migration history or depends on an earlier decision.

## Decide whether an ADR is needed

Write or amend an ADR only for a durable decision involving:

- business authority or lifecycle ownership;
- security, tenant isolation, credentials, or trust boundaries;
- cross-process or cross-runtime contracts;
- destructive or staged persistence migration;
- failure, retry, reconciliation, or irreversible side-effect guarantees.

Do not create an ADR for a local adapter change, field rename, isolated bug fix, dead-code deletion,
or behavior already governed by an existing decision.

## Plan only real boundaries

- A bounded STANDARD change may use one design-and-implementation commit.
- A STRICT change may use a concise tracked plan when it has independently recoverable migration,
  rollout, or rollback phases.
- Split at authority, compatibility, deployment, or recovery boundaries. Do not impose a fixed number
  of Work Units or a planning-only commit before every implementation.
- Do not fan one decision into multiple historical status documents. Update only the authoritative
  plan and the minimal recovery pointer needed by another agent.

## Non-negotiable architecture boundaries

- Java and PostgreSQL own authoritative Organization, User, Agent, Conversation, Project, Task,
  Plan, Memory, Governance, Tool Effect, and Runtime state.
- TypeScript multi-agent orchestration and Python sandbox workers are replaceable compute, not
  business authority.
- Modules use public APIs and immutable contracts, never another module's DAO, mapper, or tables.
- Provider, MCP, and user credentials remain encrypted, tenant-scoped, and unavailable to workers
  except through least-privilege execution contracts.
- Durable external effects retain idempotency, audit, and `UNKNOWN` reconciliation semantics.
- Workspace and Git mutation retain isolation and compare-and-set ownership rules.
- Applied Flyway migrations are immutable, and retired V1 runtimes do not return.

## Verification and handoff

Use the development Skill to implement and the verification Skill to select risk-proportional proof.
Synchronize `CURRENT.md`, the active ExecPlan, and status documents only when a tracked STRICT
milestone starts, completes, or changes scope. Routine FAST and STANDARD work must not create or
rewrite historical milestone journals.
