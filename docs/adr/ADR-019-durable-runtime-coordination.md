# ADR-019: PostgreSQL Runtime Lease, Fencing, Continuation, and SSE Cursor

- Status: Accepted
- Date: 2026-08-23
- Scope: M23-PR1

PostgreSQL is the only authoritative coordination and recovery store for AgentRun work.
Each active worker claim has a UUID lease token, owner, database-clock expiry, revision,
and monotonically increasing fencing token. Reclaiming an expired lease always increments
the fence. Worker-driven AgentRun mutations require both expected Run revision and the
currently active token/fence; a stale worker cannot commit after another replica reclaims
the Run.

Durable Continuations are deduplicated per Run, claimed with `FOR UPDATE SKIP LOCKED`, and
acquire the Run Worker Lease in the same transaction. Claims heartbeat, complete, retry,
or fail only while their token/fence remains current. The built-in `RESUME_RUN` worker is
safe to run on every platform-server replica. Lock acquisition order is Run, then Lease,
then Continuation to avoid inverse-order deadlocks with terminal Run cleanup.

RunEvent append locks the Run row and allocates one monotonic sequence in the same
transaction. HTTP replay treats the sequence as an exclusive cursor; SSE emits it as the
event ID and honors `Last-Event-ID`, so reconnecting to another replica replays only
missing PostgreSQL events.

Redis, process locks, LangGraph checkpoints, and Trace data are not recovery truth.
M23 does not make external Provider/Tool effects exactly-once; their existing ledgers and
`UNKNOWN` reconciliation rules remain authoritative. The compatibility Chat response SSE
also remains, while durable Runtime event streaming is a separate endpoint.
