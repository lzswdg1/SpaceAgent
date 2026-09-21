# ADR-077: AgentVersion activation schedule cancellation

- Status: Accepted
- Date: 2026-09-10
- Milestone: M66-PR1

## Context

M63-PR1 introduced independently approved, lease-fenced AgentVersion activation, deprecation and
rollback schedules. The schedule state model included `CANCELLED`, but no command, transition,
actor/reason evidence, persistence CAS or public control could produce that state. Hiding a schedule
in React would not cancel Java/PostgreSQL authority, while changing a row after a Worker claim could
falsely claim that an already-started effect was stopped.

## Decision

- KEEP Agent as owner of the schedule aggregate, review binding, cancellation command and evidence.
  Identity continues to authorize active Organization membership and OWNER/ADMIN role. Integration
  remains an HTTP adapter; React remains a transient confirmation and authoritative-refresh client.
- Add one transition only: `SCHEDULED -> CANCELLED`. It increments the schedule revision, sets
  `completedAt == cancelledAt`, records `cancelledBy`, and stores a SHA-256 of a trimmed, non-empty,
  at-most-500-character reason. Raw cancellation text is never durable schedule evidence.
- Treat an already `CANCELLED` schedule as an idempotent replay and return its current evidence.
  A different reason or stale original revision cannot rewrite the first cancellation evidence.
- Treat `CLAIMED` as the irreversible Worker-start boundary for this control. CLAIMED, SUCCEEDED,
  FAILED, UNKNOWN and EXPIRED schedules cannot transition to CANCELLED.
- Bind every request and CAS to tenant, Review ID, Schedule ID, AgentVersion, expected revision and
  actor. Cross-scope lookup fails without exposing another Organization's schedule.
- Add only forward migration V1078. It appends cancellation evidence columns and constraints without
  modifying V1065 or overwriting created/reviewed/claimed/terminal evidence. Upgrade fails closed if
  an impossible pre-M66 CANCELLED row exists rather than fabricating an actor or reason.
- Cancellation and Worker claim use competing PostgreSQL `state='SCHEDULED' AND revision=?` updates.
  Row locking/order is left to PostgreSQL; exactly one operation can win and the loser reloads the
  authoritative row for a bounded 409 or idempotent replay response.

## Failure semantics

- stale revision: `409 AGENT_VERSION_SCHEDULE_REVISION_CONFLICT`;
- Worker already claimed: `409 AGENT_VERSION_SCHEDULE_ALREADY_CLAIMED`;
- other immutable terminal state: `409 AGENT_VERSION_SCHEDULE_TERMINAL`;
- wrong tenant/Review/Schedule/AgentVersion: bounded not-found/forbidden response;
- ambiguous HTTP response: the browser reloads the exact review schedule list and never repeats the
  cancel mutation automatically.

## Consequences

Existing scheduled and terminal rows keep their original evidence. Existing Runs and immutable
AgentVersion pins never change. Cancellation provides no Worker interruption, Tool/Provider rollback,
external-effect retry or relaxation of the exact approved-review requirement.
