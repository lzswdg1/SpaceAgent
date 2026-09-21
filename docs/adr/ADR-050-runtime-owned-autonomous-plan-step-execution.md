# ADR-050: Runtime owns durable autonomous PlanStep execution

- Status: Accepted
- Date: 2026-09-06
- Milestone: M51-PR4

## Context

M51-PR3 creates a confirmed Blueprint, Root/Child Tasks and active TaskPlan, while M21/M33 expose a
manual Coding loop. No durable worker currently advances a PlanStep through model-selected reads,
edits, tests, approval waits and review. Several Workspace File/Document/Git operations also still
touch the managed worktree from the Java host, despite ProjectDirectory being the Sandbox boundary.

## Decision

Runtime owns one durable `ProjectCodingJob` per requested PlanStep execution. It pins tenant, owner,
ProjectDirectory, Conversation, SourceRepository, Root/Child Task, TaskPlan/PlanStep, coding Agent and
immutable AgentVersion, plus a distinct reviewer AgentVersion. PostgreSQL idempotency, leases and
fencing select one worker; Runtime Checkpoint/RunEvent, ModelCallLedger and ToolExecutionLedger retain
the detailed execution evidence.

Each Child Task receives its own Project-owned writable managed Workspace. The worker runs a bounded
model action loop using only tools enabled on the pinned AgentVersion. Every Workspace byte/Git/
document/coding operation crosses the existing authenticated Sandbox compute API. The immutable
Sandbox image contains a narrow `spaceagent-workspace-tool`; it uses mature document libraries but
has no database, credential, network or lifecycle authority. Java validates scope and claims the
effect before dispatch. The child mount is exact, writable only for mutation operations, and
networkless.

Governance remains the exact-operation authority. If an action needs approval, the Job stores the
stable Tool call, releases its lease and waits. Resume accepts only the exact approved request and
replays the same Tool identity; no effect occurs before approval.

Completion is two-phase. Coding first prepares idempotent Patch, Commit Proposal, Test and Acceptance
Artifacts without completing the Run. A distinct reviewer model evaluates only bounded Artifact and
criteria evidence. `CHANGES_REQUESTED` returns bounded feedback to the same Run for a limited revision
round. `APPROVED` records AgentReview, completes the Coding Run (therefore the Project-owned PlanStep)
and invokes the existing reviewed SourceMerge preparation. The merge remains local/manual-first;
remote state is never updated.

## Failure and compatibility

External Git, Sandbox and Provider work never runs under a database row lock. Stable logical call
IDs make terminal replay safe. An ambiguous Model/Tool result blocks the Job and is never blindly
retried. Workspace provisioning and merge preparation are idempotently rediscovered after restart;
Run attachment and durable lifecycle writes are transactionally fenced.

Existing manual Coding APIs keep their behavior. Local Bridge execution, remote push/PR/merge,
cross-Conversation handoff, Project-memory consolidation and general Workspace archival remain later
work.
