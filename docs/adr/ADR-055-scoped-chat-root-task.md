# ADR-055: Chat uses the shared Task aggregate without a hidden Project

> M54-PR2 follow-up: ADR-056 supersedes the temporary restriction that CHAT Root Tasks cannot own
> a TaskPlan. The no-hidden-Project and Java authority decisions remain unchanged.

- Status: Accepted
- Date: 2026-09-06
- Milestone: M54-PR1

## Context

The target product says every executable Chat goal is a Root Task, but the implemented Task schema
requires a Project. Generic Chat therefore persisted Conversation, Message and AgentRun without a
durable goal entity. Creating an invisible Project per user or Conversation would distort Project
membership, cleanup, Workspace and source-code semantics; creating a second ChatTask model would
duplicate Task lifecycle and make later planning convergence harder.

## Decision

Project continues to own the Task aggregate, now with an explicit mutually exclusive scope:

- PROJECT: `projectId` is present; parent Task, TaskPlan, PlanStep and Workspace behavior is unchanged.
- CHAT: tenant, owner, Conversation and source USER Message are present; project, parent and current
  TaskPlan are absent.

Each ordinary Chat Message reservation creates or reuses one Root Task by unique source Message ID.
The exact goal is the bounded user request. The Task begins IN_PROGRESS, Conversation atomically
validates and focuses it through the public Task API, and Runtime validates then immutably pins it in
`AgentRun.chatTaskId`. Successful execution completes it; failed execution fails it; approval and
UNKNOWN waits leave it IN_PROGRESS. A later Message creates another Root Task and changes only the
Conversation focus, preserving history.

V1049 backfills tenant and owner for existing Project Tasks, makes project nullable only for CHAT
scope, and adds source/Conversation/Run constraints. TaskPlan remains Project-only. Chat Task
deletion follows explicit Conversation erasure; Run deletion/purge occurs first, and the FK uses
SET NULL only at that explicit destructive boundary.

## Consequences

Conversation remains a container and Runtime remains the orchestrator. No model, browser,
TypeScript worker or hidden Project owns Task identity. This milestone creates no automatic
multi-step TaskPlan; Planner-generated Chat plans remain a separate increment.
