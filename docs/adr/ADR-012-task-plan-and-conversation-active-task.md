# ADR-012: TaskPlan and Conversation Active Task Foundation

- Status: Accepted
- Date: 2026-08-22
- Scope: M16-PR1

## Context

Project-owned Task intent and lifecycle already exist, while Conversation has only a
legacy unvalidated `taskId` compatibility field. There is no versioned TaskPlan,
normalized PlanStep DAG, approval state, or authoritative current-plan pointer. Starting
TypeScript Multi-Agent before these Java-owned contracts would make orchestration state
the accidental source of truth.

## Decision

- Project owns TaskPlan, PlanStep, dependencies, approval fields, and the Root Task
  current-plan pointer in PostgreSQL.
- Conversation owns a separate nullable `activeTaskId`. It validates the Task through the
  public Task Application API but does not execute or mutate TaskPlan state.
- M16-PR1 uses existing Project-scoped Tasks. A Plan Root is an existing Task; each Step
  references a direct child Task of that Root in the same Project.
- Plan structure is immutable after creation. A new strategy creates the next version.
- Plan lifecycle is `DRAFT -> PROPOSED -> APPROVED -> ACTIVE -> COMPLETED`; CANCELLED is
  terminal from any non-terminal state. Approval and activation require Project task
  management permission.
- Dependencies are normalized rows and validated as a DAG before persistence. Only one
  ACTIVE plan may exist per Root Task.
- Activating a plan atomically writes `Task.currentTaskPlanId`. This milestone does not
  start AgentRun, execute PlanStep, or change Task state.

## Compatibility

- Existing Conversation `taskId` remains as a legacy compatibility field until M18
  UUID/FK convergence. `activeTaskId` is additive and authoritative for new Task focus.
- Existing Project/Task APIs and lifecycle remain compatible.
- All new HTTP controllers remain Application-API adapters and never access repositories.

## Deferred

- Automatic Chat-mode Root Task creation, Task-scoped Runtime, PlanStep execution/state
  transitions, assignment, retry/timeout policies, approval comments, and RunEvent are
  later milestones.
- TypeScript may propose plans in M17 but Java will validate and persist them through the
  M16 contract.
