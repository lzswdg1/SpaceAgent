# ADR-063: Project Plan Execution Control State Machine

- Status: Accepted
- Date: 2026-09-07
- Owners: Runtime / Project Integration
- Scope: M57-PR2-U01

## Context

M57-PR1 made `ProjectPlanExecution` the durable Runtime execution authority, but its lifecycle
only described start and outcome (`READY`, `RUNNING`, `COMPLETED`, `FAILED`, `BLOCKED`). A long
running Project plan therefore had no domain contract for a user pause, a safe resume, or a
fenced cancellation. The control request must be durable in the next persistence unit without
letting a request claim that active work has already stopped.

## Decision

Runtime owns a framework-independent control state machine. The current state and the requested
`desiredState` are explicit:

| Current state | Desired state | Meaning |
| --- | --- | --- |
| `READY`, `RUNNING` | `RUNNING` | execution may make progress |
| `PAUSING`, `PAUSED` | `PAUSED` | pause requested or acknowledged at a safe boundary |
| `CANCELLING`, `CANCELLED` | `CANCELLED` | cancellation requested or acknowledged at a safe boundary |
| `COMPLETED`, `FAILED` | `RUNNING` | immutable terminal outcome; no control mutation |
| `BLOCKED` | prior desired state | fail-closed evidence requires reconciliation |

`requestPause` and `requestCancel` only record intent (`PAUSING`/`CANCELLING`). A Runtime worker
must separately acknowledge `PAUSED`/`CANCELLED` after the side-effect-safe boundary. `resume`
is legal only from `PAUSED`; it returns to `RUNNING` and clears the control reason. Repeating an
already requested or acknowledged command is idempotent and does not advance `revision`.

`UNKNOWN_EFFECT`, `AMBIGUOUS_EFFECT`, and `LEASE_LOST` use only the fail-closed `BLOCKED` path.
They never create a successor, claim completion, or silently resume/cancel the execution.
`BLOCKED` can resume only through a later reconciliation decision; ordinary pause/resume/cancel
commands cannot bypass that boundary. Invalid, terminal, missing-reason and stale-revision
conditions are explicit failures and do not mutate the domain state.

## Ownership and compatibility

Java Runtime remains authoritative for execution control. Project continues to own TaskPlan,
PlanStep, Task and Workspace metadata. This unit adds the domain/API semantics and focused domain
proof; M57-PR2-U02 persists the control fields through V1053 and the existing revision-CAS
Repository path. M57-PR2-U03 integrates pause checks into the Coordinator, writes a bounded
safe-boundary checkpoint, releases the active Job lease and acknowledges `PAUSED` before returning.
M57-PR2-U04 revalidates the active Plan/Step/Job, Workspace, AgentVersion, Run, pause checkpoint,
Model/Tool Ledgers and Approval state before an expected-revision `PAUSED -> RUNNING` transition.
Validation failure records `BLOCKED`. M57-PR2-U05 adds pending/expired Job cancellation, active Run
lease-fenced cancellation, Project Plan/Step synchronization and scheduled PAUSING/CANCELLING recovery.
UNKNOWN/AMBIGUOUS and proven SourceMerge evidence block cancellation rather than being retried or
rolled back. M57-PR2-U06 completes authenticated owner-scoped HTTP controls and Runtime cleanup.
Pause, resume and cancel require the expected revision and acknowledge intent with `202 Accepted`.
The `control` and `trace` reads return a redacted Runtime projection: state, desired state, safe
error code, control-reason presence, revision, active Job count and timestamps. Raw control
reasons never cross this boundary. Organization/User cleanup quiesces all non-terminal control
states before deleting Handoff, Job and execution rows.

The existing M57-PR1 execution lifecycle remains source-compatible while the new control value
object is introduced. Existing historical Jobs are not retroactively assigned a control state.

## Consequences

- PostgreSQL persists control state, desired state, reason and revision in V1053; applied V1052
  remains immutable.
- Pause/cancel callers receive an acknowledged intent, not a false claim that effects stopped.
- Recovery and reconciliation can distinguish a requested transition from a proven terminal state.
- No frontend, Provider, Git, Tool, MCP, TypeScript or Python ownership changes are introduced.

## M57-PR2-U05R Corrective Clarification

- Deterministic Coding failure terminates the execution as `FAILED`; only UNKNOWN, AMBIGUOUS,
  lease-loss or other proof-required effects use `BLOCKED`.
- `BLOCKED` retains the prior desired state and a bounded safe reason. Generic reset cannot leave
  BLOCKED; a future explicit reconciliation path must supply the proof.
- Reviewed completion re-locks the execution row immediately before Step/Run/Job/successor writes.
  If pause or cancellation won that lock order, no completion mutation occurs and the existing
  safe-boundary control path handles the Job. A pause before Workspace/Run creation resumes by
  revalidating the immutable binding instead of requiring a nonexistent checkpoint.
