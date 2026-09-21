# ADR-022: PostgreSQL-clock Automation through Runtime Continuation

- Status: Accepted
- Date: 2026-08-23
- Milestone: M24-PR3

## Context

The React client already exposed Scheduled Tasks and execution history, but no active Java
workflow existed. A browser timer, Redis/Bull queue, or per-replica in-memory cron registry
would create a second source of truth and duplicate work under Active-Active deployment.
Running Chat directly from a scheduler would also bypass M23 leases/fencing and M24-PR2
Governance.

## Decision

Java Automation owns Organization-scoped `AutomationSchedule` and immutable occurrence
`AutomationExecution` state in PostgreSQL. Spring `CronExpression` is the parser; no custom
cron engine is implemented. PostgreSQL `clock_timestamp()`, due-row `FOR UPDATE SKIP LOCKED`,
schedule revision CAS, and unique `(schedule_id, fire_key)` determine which occurrence is
materialized. The Spring `@Scheduled` callback only wakes this durable scanner.

Every occurrence resolves and pins the current published AgentVersion, calculates an exact
operation digest, and calls the `AUTOMATION_TRIGGER` Governance action. An approval-required
execution remains durable and creates no Conversation/Run until the approved capability is
atomically consumed.

An authorized occurrence creates Conversation + AgentRun and enqueues a deduplicated M23
`AUTOMATION_EXECUTION` Continuation in the same database transaction. Runtime dispatches
module handlers through `RuntimeContinuationHandler`, so Runtime imports no Automation
implementation. The worker resumes the pre-created Run with its active lease/fence, invokes
prepared Chat on that Run, then applies a fenced terminal mutation before completing the
Continuation.

AutomationExecution is an at-most-once ledger around Provider/Tool effects. The execution
claim commits before prepared Chat. A known response becomes `SUCCEEDED` or `FAILED`; a lost
worker/ambiguous crash becomes `UNKNOWN` and is not blindly executed again. The legacy
`maxRetries` field remains compatibility metadata; automatic business retries are deferred
until effect-aware reconciliation can prove they are safe. `bullJobId` is always null.

Missed periodic times are coalesced into one due occurrence and the next time is calculated
after the current database time, preventing unbounded catch-up storms.

## Consequences

- PostgreSQL is schedule, occurrence, approval-wait and execution truth.
- Multiple platform replicas may poll safely without a leader or duplicate local scheduler.
- Redis/Bull/frontend clocks are not required for correctness.
- Schedule deletion is an archive operation; execution history remains for audit.
- V1022 adds the Runtime continuation type and V1023 adds Automation tables/constraints.
- Generic webhook/repository-event triggers and effect-aware automatic retries remain later
  extensions of the same occurrence/Continuation model.
