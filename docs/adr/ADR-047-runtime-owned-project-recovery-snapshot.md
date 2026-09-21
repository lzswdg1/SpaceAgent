# ADR-047: Runtime owns immutable Project execution recovery snapshots

- Status: Accepted
- Date: 2026-09-05
- Milestone: M51-PR2

## Context

M51-PR1 made Project Coding Runs identify one exact ProjectDirectory and Workspace. Recovery still
required a caller to independently rediscover Blueprint, TaskPlan, Workspace/Git, Conversation,
Checkpoint, Artifact, Model/Tool ambiguity and Approval state. Replaying a Conversation or relying
on model process memory cannot reconstruct this safely, and copying those mutable lifecycles into a
new recovery aggregate would create a second authority.

## Decision

Runtime owns one immutable `ProjectExecutionContextSnapshot` record for a strict directory-bound
Coding Run. A `CodingRecoveryPackage` is composed through the public Application APIs of Project,
Conversation, Artifact, Inference, Tooling and Governance. The canonical payload, content SHA-256,
hashed idempotency key, input-scope hash and exact Run references are persisted in PostgreSQL.

The payload is point-in-time recovery evidence, not current lifecycle state. Before any resumed
effect, Java must revalidate the referenced Blueprint, Task/PlanStep, Workspace, AgentVersion,
Ledger and Approval with its owner. Missing optional context is a named blocker; malformed scope,
Sandbox failure or evidence overflow aborts capture without persisting a partial package.

Live Git evidence is captured only by allowlisted read-only `git` commands inside the exact
`workspaces/{workspaceId}` OCI mount. The package supports HEAD, status, tracked Patch and bounded
untracked file patches. It accepts no client path and sends no Provider/MCP credential to the
Sandbox. Host Project file/Git gateways are not a recovery fallback.

The external package omits Tool arguments/results/errors, Model response/usage/error summaries, raw
Checkpoint payload and Approval operation hashes. It exposes only the owner-authorized source
Patch, safe typed references, hashes, terminal evidence, unresolved UNKNOWN records, blockers and
a deterministic next action.

## Consequences

- V1044 adds one Runtime-owned immutable table and exact AgentRun-scope composite constraint; no
  historical Run is backfilled with invented recovery content.
- Same owner/idempotency key and scope replays the original package; cross-scope reuse conflicts.
- Snapshot lookup survives Java restart while the contributing modules remain authoritative.
- Package delivery to a replacement Agent/Handoff remains M51-PR5. Automatic analysis/planning and
  the autonomous Sandbox coding loop remain M51-PR3/PR4.
