# ADR-053: Durable generic Chat Tool approval and same-Run resume

- Status: Accepted
- Date: 2026-09-06
- Milestone: M53-PR1

## Context

Governance already creates exact-operation, expiring, one-use approvals and Runtime Tool dispatch
checks them at the side-effect boundary. Generic Chat, however, is synchronous: an approval request
escapes as an exception and the outer coordinator fails the Run and its reply reservation. Passing
an approval ID in a new Chat request creates another Run and cannot faithfully resume the pending
Tool chain.

## Decision

Runtime pauses the existing AgentRun in `WAITING_FOR_USER` and appends a bounded, versioned
`chat-approval/v1` Checkpoint. It retains the exact Conversation reply reservation, pinned
AgentVersion, Chat/Tool RunSteps, model-selection snapshot, inference/context inputs, completed Tool
results, pending/remaining Tool calls and exact Approval ID needed to continue. This is authoritative
recovery state in PostgreSQL; it is not an external orchestration checkpoint or telemetry payload.

An additive owner-scoped resume command verifies tenant, owner, Run state, checkpoint schema and
the exact pending Approval ID. It acquires the existing PostgreSQL worker lease before returning the
Run to `IN_PROGRESS`. The pending Tool is invoked with the approval ID added only to its validated
arguments. Governance removes that field from the operation digest and atomically consumes the
matching capability immediately before Tool claim/effect. The same ToolCall ID preserves ledger
idempotency, and a terminal replay is returned before a second approval is requested.

If another Tool requires approval, Runtime writes a new checkpoint and waits again. After all Tools
finish, Runtime performs the bounded no-Tool synthesis call, completes the original assistant reply
reservation, ContextSnapshot and Memory evaluation, then completes the same AgentRun under the
worker fence.

## Failure and security semantics

- pending approval remains waiting; rejected, expired or mismatched approval executes no effect;
- only one concurrent resume owns the worker lease;
- Tool `UNKNOWN` is never retried; M53-PR2 adds allowlisted Workspace postcondition adapters over the
  existing internal reconciliation primitive while MCP/command outcomes remain blocked;
- checkpoint payload is size bounded and visible only through existing owner-scoped Runtime
  recovery boundaries; it never enters Admin reads, metrics, logs or OpenTelemetry;
- Java/PostgreSQL remain all authorization, persistence and side-effect authority.

## Consequences

Successful Chat contracts remain compatible and approval resume becomes crash-recoverable without
another business database. A reply may remain `ASSISTANT_PENDING` while approval is outstanding,
which is intentional durable UI state. Frontend presentation is outside this backend milestone.
