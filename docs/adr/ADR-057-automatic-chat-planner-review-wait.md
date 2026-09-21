# ADR-057: Automatic Chat planning stops at a durable review boundary

> M54-PR3B follow-up: ADR-058 completes the lease-fenced PlanStep execution and Tool-wait progress
> described as deferred below. Automatic planning remains default-off but is no longer forbidden in
> Trusted Beta when the HTTP TypeScript orchestrator is configured.

- Status: Accepted
- Date: 2026-09-06
- Milestone: M54-PR3A

## Context

M54-PR2 can persist a LangGraph `PLAN_PROPOSED`, but ordinary Chat never invokes that boundary.
Directly continuing the old single inference after creating a multi-step plan would falsely imply
that every Child Task and PlanStep had executed. Automatically marking the plan approved would also
misattribute a user decision. The complete PlanStep/Tool-wait continuation is large enough to need
its own independently recoverable increment.

## Decision

Automatic planning is an explicit, default-off Runtime policy. When enabled for an ordinary
non-Project Chat request, Java first creates the M54-PR1 Root Task and pinned AgentRun, then invokes
the existing TypeScript LangGraph boundary with only bounded Task content, pinned AgentVersion
references, capability names and limits. Provider secrets remain in Java.

If LangGraph returns `COMPLETED`, Java records that no explicit plan is required and continues the
compatible Chat path. If it returns `PLAN_PROPOSED`, M54-PR2 persists the exact proposal. Runtime
then writes `chat-plan-review/v1` with the Run, Conversation, Root Task, TaskPlan, immutable
AgentVersion and existing assistant reservation, completes the planning RunStep, and moves the same
Run to `WAITING_FOR_USER`. HTTP and SSE return `WAITING_PLAN_APPROVAL` plus the Root Task and
TaskPlan references. No normal inference or Tool effect occurs before this boundary.

Malformed, unavailable or non-persisted Planner output fails the Run and Root Task. Explicit
enablement never silently falls back to unplanned execution. Project Chat and prepared Automation
are not eligible.

## Release boundary

The Trusted Beta validator and release Compose force automatic planning off until M54-PR3B exists.
Development deployments may enable `PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED=true` to exercise the
review boundary. A PROPOSED/APPROVED/ACTIVE plan is not described as executed.

## Consequences

The Planner is now part of the real Chat lifecycle without moving durable authority to TypeScript
or lying about Step completion. M54-PR3B must resume an APPROVED and ACTIVE plan under the Runtime
worker lease, execute ready DAG Steps with exact Model/Tool evidence, and extend approval/UNKNOWN
checkpoints with plan progress before release enablement can change.
