# ADR-049: Project intake proposals precede authoritative Project intent

- Status: Accepted
- Date: 2026-09-06
- Milestone: M51-PR3

## Context

A newly imported ProjectDirectory has a SourceRepository and Conversation but no Root Task. Normal
Workspaces and Coding Runs are intentionally Task/TaskPlan/PlanStep-bound, so creating a fake Task
only to inspect the repository would make unconfirmed model output appear authoritative.

## Decision

Project owns a durable Intake Job pinned to the tenant, owner, Project, source-root Directory,
SourceRepository, Conversation, Agent and immutable AgentVersion. A PostgreSQL lease/fence lets one
worker inspect and propose. Before confirmation, no ProjectBlueprint, Task, TaskPlan or PlanStep row
is created.

The Job owns a disposable detached Git worktree under `workspaces/{jobId}`. It is not a reusable
Workspace aggregate and cannot be supplied to Coding APIs. The exact directory must be an active
SourceRepository root. The worktree is mounted read-only into the existing OCI Sandbox, with no
network and allowlisted Git commands. Local Bridge source cannot be analyzed until a client-managed
root can be materialized inside the OCI boundary.

Runtime creates a tenant/owner/Conversation-scoped intake AgentRun solely so read-only inspection
and model generation retain ToolExecutionLedger/ModelCallLedger evidence before a Task exists. The
ProjectIntakeJob remains the Project-scope authority. Model and Tool UNKNOWN block completion and
are not automatically retried.

The proposal contains domain-shaped data but no model-selected persistent IDs. Java validates all
bounds and the PlanStep DAG and hashes the normalized proposal. Exact-owner confirmation of that
hash runs one transaction that confirms a Blueprint, creates Root/Child Tasks, creates/proposes/
approves/activates the TaskPlan, focuses the Conversation and records the resulting IDs on the Job.
Rejection creates none of those entities.

## Security and failure semantics

Only bounded allowlisted manifests and documentation are inspected. Secret-shaped lines are
redacted before persistence and Provider input. The Sandbox receives no Provider/MCP credential.
External Git/Sandbox/Provider work runs outside database transactions. Job completion and the
Runtime terminal transition are fenced; a stale worker cannot publish a proposal.

## Consequences

- Existing Task-bound Workspace and Coding Run invariants remain unchanged.
- The proposal can be reviewed or rejected without contaminating Project authority.
- A confirmed Job is restart-safe and idempotently returns stable Blueprint/Task/Plan references.
- Autonomous PlanStep claiming/execution remains M51-PR4; cross-Conversation handoff, memory
  consolidation and general Workspace archival remain M51-PR5.
