# ADR-067: Versioned Durable Graph Contract

- Status: Accepted
- Date: 2026-09-08
- Milestone: M59-PR1-U01
- Owners: Runtime / TypeScript Multi-Agent

## Decision

`graph/v2` is a separate, strict boundary beside retained `multi-agent/v1`. It binds a Java-owned graph
session/run, tenant/owner, immutable bundle hash, bounded cursor and stable command IDs/input hashes.
TypeScript returns at most one compute-only command proposal and declares `ephemeral=true`; it owns no
checkpoint, database, credential, Provider, Tool or Git effect. Java will persist/claim/replay the cursor
and command ledger in M59-PR1-U02/U03 and validates every command before any owner effect.

## Consequences

U01 adds schema, golden fixtures and Java/TypeScript parsers only. It does not migrate existing v1 callers,
add persistence, invoke a Provider, or execute a Tool/Git action.

## M65-PR4 command-execution amendment

Command `inputHash` is the SHA-256 of exact graph session ID, current Java cursor sequence, command
kind and recursively key-sorted canonical payload. `bundleHash` is configuration evidence and cannot
substitute for command identity. Java accepts only `nextCursor.sequence == current + 1`, unchanged
completed IDs and an exact pending command ID before persisting the boundary.

Non-terminal commands are durably enqueued as `GRAPH_COMMAND_EXECUTION` Runtime Continuations. The
existing PostgreSQL continuation lease/fencing worker invokes a Java-owned execution handler, which
first moves the command from `PENDING` to `EXECUTING` with the exact continuation owner/token/fence.
MODEL and Tool work use existing Inference/ModelPool/Budget/ModelCallLedger and Runtime Tool/Ledger
APIs; delegation, review and handoff use existing Runtime public APIs. TypeScript remains compute-only.

A process restart that observes `EXECUTING` cannot replay an unproven effect: the command and session
become `UNKNOWN/BLOCKED`. Only the same execution fence may confirm or block a command. Confirmation
atomically advances Java's session boundary and enqueues the existing graph continuation. Legacy
pending commands upgraded by V1076 are also blocked as unproven rather than executed automatically.
