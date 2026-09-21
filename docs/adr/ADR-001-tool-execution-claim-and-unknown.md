# ADR-001: Tool Execution Claim, Fencing, and UNKNOWN Semantics

- Status: Accepted
- Date: 2026-08-22
- Scope: M10-PR1 ToolExecutionLedger only

## Context

`platform_tool_execution_ledger` uses `(agent_run_id, tool_call_id)` as its logical
unique key. Before M10-PR1, the application performed a find-then-save sequence and the
PostgreSQL adapter used an unconditional conflict update. A concurrent caller could
overwrite `input_hash` or state, two JVMs could both enter the external Tool Gateway, and
an existing `RUNNING` entry could be executed again. The schema also lacked a claim token,
lease, revision, and reconciliation evidence.

Tool execution crosses a local PostgreSQL transaction and an external process or HTTP
boundary. The external side effect cannot be committed atomically with the ledger row.

## Decision

PostgreSQL is the authoritative claim arbiter. Every ordinary call receives one explicit
decision:

- `CLAIMED`: this caller owns the only valid claim and may call the Gateway;
- `REPLAY`: a matching terminal result already exists;
- `BUSY`: another valid RUNNING lease exists;
- `UNKNOWN`: the result is ambiguous and requires reconciliation;
- `CONFLICT`: the persisted InputHash does not match.

Only `CLAIMED` enters the external Gateway. Claim, external execution, and completion are
three separate phases. Claim and completion each use a short database transaction; the
Gateway is never called while a ledger row lock is held.

## Claim Algorithm

1. Attempt `INSERT ... ON CONFLICT DO NOTHING RETURNING` with status `RUNNING`, a unique
   claim token, diagnostic owner, database-derived lease, and revision 1.
2. If the insert succeeds, commit and return `CLAIMED`.
3. Otherwise, lock the existing logical row in a short transaction and evaluate in order:
   InputHash/idempotency conflict, terminal Replay, UNKNOWN, active RUNNING Busy, then
   expired RUNNING or legacy PENDING.
4. Expired RUNNING and legacy PENDING are atomically changed to UNKNOWN. They are never
   taken over by a new executor.

`input_hash` is written only by the first insert. No ordinary claim, completion, UNKNOWN,
or reconciliation SQL updates it.

## Fencing

Every valid claim has:

- `claim_token`: a unique UUID known only to the internal application call path;
- `revision`: a monotonically increasing ledger version;
- `lease_until`: the PostgreSQL-time validity boundary.

Ordinary completion updates exactly one row only when status is RUNNING, claim token and
expected revision match, and the lease remains active. A zero-row update causes a locked
re-read; it never triggers an overwrite retry. Wrong tokens, stale revisions, UNKNOWN,
and terminal rows cannot be overwritten by ordinary completion.

## Lease

Lease time uses PostgreSQL `clock_timestamp()`, not a JVM clock. The Sandbox application
requests a lease equal to the bounded Tool timeout plus a 30-second completion-persistence
safety margin. M10-PR1 does not add heartbeat renewal or a general Worker Lease framework.

An expired lease does not authorize takeover. It changes RUNNING to UNKNOWN so a late
result cannot silently race a second external execution.

## UNKNOWN

UNKNOWN means the system cannot prove whether the external side effect occurred. It is
used when:

- a transport timeout, connection reset, or response decoding failure leaves the outcome
  indeterminate;
- the JVM disappears after claim commit and the lease later expires;
- an explicit completion arrives after lease expiry;
- a pre-M10 PENDING/RUNNING row lacks trustworthy claim metadata.

UNKNOWN is stable under ordinary claim and completion. It cannot return to PENDING or
RUNNING.

## Reconciliation

An internal Application API may transition UNKNOWN to `SUCCEEDED`, `FAILED`, `TIMED_OUT`,
or `CANCELLED`. It requires matching InputHash and revision plus structured evidence,
resolver identity, and a resolution reason. The transition persists JSONB evidence and
resolution timestamps. No public HTTP reconciliation endpoint is introduced.

## Alternatives Rejected

### Find-then-upsert

Rejected because it cannot atomically select one executor and may overwrite immutable
input or a newer state.

### JVM `synchronized`

Rejected because it protects only one process and does not coordinate platform-server
replicas.

### Redis lock

Rejected because PostgreSQL already owns the durable ledger, and a second coordination
authority would introduce split-brain and recovery ambiguity.

### Expired lease auto takeover

Rejected because the first worker may already have performed the external side effect.
Automatic takeover could duplicate it.

### Exactly-once claim

Rejected as a misleading system guarantee. A database claim can be single-holder, but
the external side effect and PostgreSQL terminal write are not one atomic transaction.

## Consequences

- Concurrent platform-server instances obtain one CLAIMED and one BUSY decision.
- Matching terminal rows are safe to Replay.
- InputHash conflicts do not mutate the persisted row.
- Crash-before-terminal becomes explicit UNKNOWN rather than automatic retry.
- Operations need reconciliation procedures for ambiguous tools.
- A long-running tool must remain within its bounded timeout because there is no heartbeat.
- Claim metadata increases schema and Application API complexity.

## Follow-up Work

- M53-PR2 implements Workspace write/delete postcondition adapters; MCP/command-specific verifiers
  remain follow-up work;
- operational views and alerts for BUSY/UNKNOWN entries;
- authentication and network isolation for sandbox-worker HTTP;
- a future general Worker Lease and durable Async Runtime;
- ModelCallLedger as a separate authorized milestone.

This ADR does not implement or claim ModelCallLedger, Async Runtime, general Worker Lease,
Project/Task/Workspace, AgentVersion, or Multi-Agent capability.
