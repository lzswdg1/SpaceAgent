# ADR-058: Chat TaskPlan execution is lease-fenced and step-evidenced

- Status: Accepted
- Date: 2026-09-06
- Milestone: M54-PR3B

## Context

M54-PR3A stops a planned Chat Run at a durable review boundary. An ACTIVE plan still needs to
execute without treating plan text as evidence, replaying completed steps, changing the pinned
AgentVersion/model selection after a Tool wait, or allowing a browser to mark steps complete.

## Decision

The owner resumes the exact TaskPlan referenced by `chat-plan-review/v1`. Java requires the same
tenant, user, Conversation, Root Task, AgentRun and AgentVersion, verifies that the plan is ACTIVE,
and acquires the existing PostgreSQL Runtime worker lease. A stale or concurrent worker cannot
continue the Run.

Runtime repeatedly selects a dependency-ready PlanStep from the Java-owned DAG. Project performs
every Step/Child Task transition through its public execution API. Each Step receives a distinct
Runtime RunStep; model calls use that RunStep for stable ModelCallLedger identity, and Tool calls
continue through Governance and ToolExecutionLedger. Bounded Step output is stored in
`chat-plan-step-completed` before the next Step. Final synthesis uses only the compiled Chat context
and completed Step evidence, then completes the original assistant reservation, TaskPlan, Root Task
and AgentRun.

`ChatToolWaitCheckpoint` gains optional plan progress: TaskPlan ID, current PlanStep ID and bounded
completed Step results. Approval and UNKNOWN reconciliation resume the same Tool call and pinned
model snapshot, complete the interrupted Step, and continue only the remaining DAG. The old
unplanned checkpoint payload remains compatible because plan progress is optional.

## Failure and release semantics

Wrong/inactive/replaced plans, stale review checkpoints, cross-owner requests, incomplete
dependencies and concurrent leases fail closed. Runtime failures fail the current Step and Root
Task and cancel the active plan; the client cannot submit Step state or completed evidence.

No schema migration is required; V1050 already owns Chat plans and Runtime checkpoints are JSON.
Trusted Beta may enable automatic planning only with the TypeScript HTTP orchestrator configured.
The default remains off.

## Consequences

Chat now implements Root Task -> reviewed TaskPlan -> Child Task/PlanStep execution -> final
synthesis with durable Java/PostgreSQL authority. TypeScript remains proposal-only and neither
browser state nor complete-Conversation replay is a recovery source.
