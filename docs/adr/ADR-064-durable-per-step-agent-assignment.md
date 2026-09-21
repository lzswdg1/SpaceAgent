# ADR-064: Durable Per-Step Agent Assignment

- Status: Accepted
- Date: 2026-09-08
- Milestone: M58-PR1-U01
- Owners: Runtime / Project / Agent

## Context

`PlanStep` is Project-owned structural intent and currently carries only `preferredAgentId`.
`ProjectPlanExecution` and `ProjectCodingJob` pin one execution-time AgentVersion and reviewer, but
they do not provide a durable, independently explainable Agent binding for each PlanStep. Reusing a
mutable current Agent, ModelPool or capability view would make a restarted, handed-off or historical
step ambiguous after an AgentVersion is deprecated or an Agent configuration changes.

## Decision

Runtime owns an immutable `ProjectPlanStepAssignment` snapshot for exactly one Project PlanStep.
Project supplies the tenant/owner/project/TaskPlan/PlanStep scope through its public API and remains
the authority for PlanStep lifecycle. Agent validates new bindings through its public API; Runtime
does not read Agent persistence.

Every snapshot records its positive revision, selection source (`PLAN_DEFAULT`, `STEP_OVERRIDE` or
future `HANDOFF`), exact primary and distinct reviewer Agent/AgentVersion IDs, exact ModelPool ID,
capability hash, Agent configuration hash and a canonical aggregate assignment hash. The aggregate
hash includes the full scope and revision, so an evidence tuple cannot be reused for a different
tenant, plan, step or revision. A plan-default is copied into a per-step snapshot when resolved;
it is not a mutable fallback lookup. A step override is likewise immutable.

`preferredAgentId` remains a Project selection constraint only. It never creates a persistent ID,
cannot select a version and cannot silently replace an unavailable assignment. A missing or invalid
binding will later use `ASSIGNMENT_REQUIRED`/`BLOCKED`; M58-PR1-U03 performs published/same-tenant
AgentVersion, ModelPool, Tool, Skill, Knowledge and Sandbox validation before persistence or
dispatch. A handoff will create a higher immutable assignment revision while retaining prior
evidence; it never edits an existing snapshot.

## Consequences

- M58-PR1-U01 introduces only framework-independent Runtime domain and public API contracts; it
  adds no Flyway migration, repository, HTTP route, dispatch path or external side effect.
- M58-PR1-U02 will persist the immutable snapshot in V1054. U03 validates assignments, U04 binds
  dispatch/handoff, and U05 exposes safe projections and cleanup.
- Historical assignment evidence remains explainable after AgentVersion deprecation or archive;
  only creation of a new assignment requires a currently valid published binding.
- Java/PostgreSQL remain the sole future business authority. No frontend, Provider, MCP, Git,
  TypeScript or Python process gains assignment ownership.
