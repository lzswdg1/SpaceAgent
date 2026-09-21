# ADR-021: Organization Governance and exact-operation approval

- Status: Accepted
- Date: 2026-08-23
- Milestone: M24-PR2

## Context

Coding Runtime could write/delete files or run commands after a ToolExecutionLedger
claim, but an Organization had no authoritative policy or human approval lifecycle.
An approval record that is not checked at the side-effect boundary would provide audit
appearance without security. A reusable approval token would also permit confused-deputy
or replay attacks against a different operation.

## Decision

Java Governance owns Organization policy and ApprovalRequest/Decision state in
PostgreSQL. Every request binds tenant, requester, action type, resource type/id, and a
canonical SHA-256 operation digest. An approved request is an expiring, one-use capability;
PostgreSQL compare-and-set changes `APPROVED` to `CONSUMED` only when every bound field
matches.

OWNER/ADMIN manage policy and decisions through the Governance Application API. Optional
separation of duties prevents self-approval. Policy writes use revision compare-and-set.
Default gates are disabled to preserve compatible behavior.

Coding Runtime checks Governance before creating a RunStep, claiming the Tool ledger, or
calling the Workspace gateway for `WRITE_FILE`, `DELETE_FILE`, and `RUN_COMMAND`. The
operation digest includes Run, Workspace, toolCall ID and canonical arguments/content.
Terminal Tool ledger replay is checked first because it performs no new side effect and
must not consume a second approval.

Automation, network-capable tools, and source merge use policy fields as pre-configuration
only until their authoritative execution boundaries exist. Automation must later dispatch
through M23 Continuation/fencing and call the same Governance API before enqueue/trigger.
The current synchronous Chat sandbox is not gated in this increment because it lacks a
durable approval/resume command and its active fallback only supports `echo/fail`.

## Consequences

- PostgreSQL, not frontend/Redis/Trace/TypeScript, is decision authority.
- A consumed or mismatched request cannot authorize another operation.
- A crash after consumption but before the side effect may require a new approval; this
  is intentionally fail-closed rather than reusing a capability ambiguously.
- Governance never imports Runtime/Tool/Project repositories; Runtime consumes its public
  Application API.
- V1021 creates `platform_governance_policies` and `platform_approval_requests`.
