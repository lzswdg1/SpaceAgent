# ADR-002: Durable Model Call Claim, Replay, and UNKNOWN Semantics

- Status: Accepted
- Date: 2026-08-22
- Scope: M10-PR2 default Java Chat inference only

## Context

The default Chat runtime previously called the configured model Provider directly after
context compilation. A repeated or recovered entry into that inference phase had no
durable logical call identity, no cross-JVM claim, and no authoritative response, Usage,
or Provider Request ID record. A process could also disappear after the Provider had
completed but before the inference checkpoint was committed.

Provider execution and local PostgreSQL persistence cannot be one atomic transaction.
Provider billing or generation may have completed even when the platform has no terminal
ledger row.

## Decision

Inference owns an independent `platform_model_call_ledger`, keyed by
`(agent_run_id, logical_call_id)`. The default Chat call uses
`runStepId + ":inference:0"`, so re-entering the same phase of the same AgentRun resolves
the same row. A new client HTTP request still creates a new AgentRun and therefore a new
logical call.

`request_hash` is SHA-256 over a normalized, secret-free request containing Provider ID,
Model ID, ordered messages (including system/context/user content), effective model
parameters, and the full tool definition/schema. Map keys are sorted. API keys,
Authorization headers, and decrypted Provider secrets are excluded.

## Claim and Fencing

PostgreSQL returns exactly one of `CLAIMED`, `REPLAY`, `BUSY`, `UNKNOWN`, or `CONFLICT`.
The first caller atomically inserts RUNNING with a UUID claim token, JVM diagnostic owner,
database-time lease, and revision 1. A conflicting insert does not update any existing
column; the existing row is locked only for the short decision transaction.

Only CLAIMED calls the Provider, after the claim transaction commits. Completion requires
RUNNING, matching token and revision, and an unexpired PostgreSQL-time lease. A zero-row
completion re-reads current state and never overwrites it. Provider HTTP timeout is
bounded to 1-600 seconds; the claim lease adds a bounded 1-120 second safety margin and
cannot exceed 720 seconds. No heartbeat or automatic lease takeover is introduced.

## Response and Usage Replay

SUCCEEDED stores a standardized JSON response containing assistant content, tool calls,
and finish reason. A separate Usage JSON stores input, output, total, and other
Provider-returned usage fields; Provider Request ID has its own column. These fields are
sufficient to reconstruct the current provider-neutral `InferenceExecutionResult`.
Secrets and raw HTTP headers are never copied into the ledger.

Matching terminal calls Replay without Provider execution. FAILED, TIMED_OUT, and
CANCELLED replay the saved controlled failure. A different request hash returns Conflict
without mutating the original hash or terminal state.

## UNKNOWN

An active RUNNING lease returns Busy. An expired RUNNING lease becomes UNKNOWN and is
never taken over. Transport reset/read-timeout ambiguity, truncated or invalid responses,
and inability to encode a successful Provider response also become UNKNOWN. If the
Provider returned but the terminal database write itself fails, the row remains RUNNING;
after lease expiry, the next inspection changes it to UNKNOWN.

UNKNOWN never calls the Provider automatically. M10-PR2 exposes no public reconciliation
endpoint and does not implement automatic `/recover` continuation; future manual or
Provider-specific reconciliation must be separately authorized.

## Error Classification

Pre-dispatch validation, client preparation, request serialization, and parseable
Provider 4xx responses are terminal FAILED. Explicit caller cancellation is CANCELLED.
Provider 5xx, post-dispatch transport, or response ambiguity is UNKNOWN. Controlled summaries
are persisted; raw transport exception messages, prompts, responses, and credentials are
not logged or stored as error text.

## Why This Is Not Exactly-Once

The claim provides at most one valid local Claim Holder for a logical ModelCall, but the
Provider operation and PostgreSQL completion are separate commits. A Provider may finish
and charge while the platform later records UNKNOWN. Provider-side idempotency is not
assumed. Therefore neither model generation nor billing is guaranteed exactly-once.

## Consequences and Deferred Work

- Same-run logical re-entry can Claim, Replay, or stop safely at Busy/Unknown/Conflict.
- Current Chat HTTP/SSE DTOs and endpoints do not change.
- Cross-Run HTTP request idempotency remains unimplemented.
- Default Chat `/recover` still reconstructs state only and does not continue execution.
- ModelPool, routing/fallback, AgentVersion, Project/Task, Async Runtime, and generic
  workflow/heartbeat frameworks remain outside this decision.
