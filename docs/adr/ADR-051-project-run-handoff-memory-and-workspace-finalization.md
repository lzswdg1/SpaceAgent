# ADR-051: Runtime-owned Project handoff coordinates existing recovery, memory and Workspace owners

- Status: Accepted
- Date: 2026-09-06
- Milestone: M51-PR5

## Context

M51-PR2 can capture a complete immutable Coding Recovery Package and M51-PR4 can autonomously run
one PlanStep, but a blocked or approval-paused execution cannot yet be continued in another
Conversation or by another Agent/model without manually reconstructing state. Completed task
Workspaces also remain live until a user explicitly deletes them, and Project Memory is not compiled
into the autonomous Coding context.

The existing generic Runtime Handoff represents Supervisor/child-Agent delegation. Reusing it as a
user-directed Project execution lifecycle would omit tenant, ProjectDirectory, Recovery Snapshot,
Coding Job, idempotency and Workspace finalization guarantees.

## Decision

Runtime owns a distinct durable `ProjectRunHandoff`. It pins the complete source execution scope,
the immutable M51-PR2 Recovery Snapshot, target same-directory Conversation, target published
AgentVersion, target ProjectCodingJob/AgentRun and the exact reusable Workspace. The target
AgentVersion's immutable ModelPool binding is the model handoff boundary. PostgreSQL owns hashed
idempotency, revision, finalization claim/lease and monotonic fencing.

Only a Coding Job with no active lease and a paused or blocked state may hand off. Runtime marks the
source Coding Job `HANDED_OFF` and terminally cancels its AgentRun with an exact handoff marker
without cancelling the Project-owned IN_PROGRESS PlanStep,
then enqueues a new Coding Job for that same step. The new job reuses the exact Workspace and compiles
the immutable Recovery Package plus bounded Project Memory into its initial model context. UNKNOWN
Model/Tool evidence remains a blocker/evidence reference and is never blindly repeated.

After the target job completes its normal Test/Acceptance/distinct-Reviewer/SourceMerge path, a
separate finalization claim writes an idempotent, bounded Project Memory summary through the Memory
Application API and archives the managed Workspace through the Project Application API. External
Git/worktree cleanup never runs under a database transaction. A crash between cleanup and fenced
completion is safe because memory is keyed deterministically and Workspace archive is idempotent.

## Ownership and compatibility

- Runtime owns ProjectRunHandoff state and source/target Run links.
- Project owns TaskPlan/PlanStep, Workspace metadata and managed Git cleanup.
- Memory owns durable Project memories; the handoff stores only a deterministic memory key.
- Conversation and Agent validate their own target identities and remain non-orchestrators.
- Integration coordinates only public Application APIs.

Existing generic Multi-Agent Handoff and manual Coding APIs are unchanged. Remote Git refs,
automatic merge, Local Bridge execution, frontend work and a second database/checkpointer remain
out of scope.
