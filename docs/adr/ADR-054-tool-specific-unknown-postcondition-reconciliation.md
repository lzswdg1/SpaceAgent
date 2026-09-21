# ADR-054: Tool-specific UNKNOWN reconciliation uses read-only postconditions

- Status: Accepted
- Date: 2026-09-06
- Milestone: M53-PR2

## Context

ToolExecutionLedger correctly makes an ambiguous side effect stable `UNKNOWN`, but its low-level
reconciliation API accepts a terminal state and evidence from trusted Java callers. Exposing that
primitive to a browser would create an arbitrary state editor; retrying the original effect could
duplicate it. M53-PR1 also needs a safe way to continue a Chat paused by an UNKNOWN Tool.

## Decision

Runtime owns a reusable reconciliation coordinator and calls Tooling only through its public
ledger API. The coordinator derives tenant, owner, Project, Task and Workspace from the immutable
AgentRun and persisted Tool input. Clients provide only the expected Ledger revision and a bounded
human reason; they cannot select the terminal status, result, path or evidence.

The first allowlist contains effects with a locally observable desired postcondition:

- `document_write`: read the exact Workspace path in the OCI Sandbox. Text/Markdown/HTML require
  exact UTF-8 content and size; DOCX requires exact normalized extracted paragraph text.
- `workspace-write-file`: read the Run-bound Workspace path and require exact UTF-8 content/size.
- `workspace-delete-file`: read the Run-bound path and accept only the trustworthy, non-ambiguous
  `WORKSPACE_FILE_NOT_FOUND` result.

A matching postcondition produces only hashed/bounded evidence and atomically reconciles UNKNOWN
to `SUCCEEDED` using the persisted InputHash and expected revision. A mismatch, truncated read,
ambiguous verifier error, changed scope or stale revision leaves UNKNOWN unchanged. Dynamic MCP
mutations, arbitrary commands and other Tools have no generic proof protocol and remain unsupported.

Chat stores `chat-tool-unknown/v1` in the existing Runtime checkpoint stream and returns
`WAITING_RECONCILIATION`. The reconcile command holds the PostgreSQL Runtime worker lease. After
successful reconciliation, normal Tool dispatch sees a terminal ledger Replay before Governance or
effect execution, then completes the original synthesis, reply reservation and AgentRun.

## Consequences

Reconciliation proves that the desired state exists now; it does not claim database/external-effect
exactly-once or who produced that state. No mutation is called during verification. Tooling remains
ledger authority, Project remains Workspace-byte authority, Runtime coordinates, and unsupported
UNKNOWN rows continue to block instead of being guessed or retried.
