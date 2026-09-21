# ADR-061: Durable Project Plan Auto-dispatch

- Status: Accepted
- Date: 2026-09-07
- Milestone: M56-PR1

## Context

M51-PR4 delivered a durable Project Coding loop for one explicitly selected PlanStep, but starting
an approved Project TaskPlan still required a client to enqueue every step. The browser could
therefore become an accidental scheduler, and a completed Coding Job did not close the Project-owned
PlanStep, Child Task, TaskPlan and Root Task lifecycle as one authoritative workflow.

The existing ProjectCodingJob already persists the exact ProjectDirectory, Conversation,
SourceRepository, AgentVersion, reviewer AgentVersion and base ref. TaskPlan and ProjectCodingJob
are stored in the same PostgreSQL database, so no new queue or distributed authority is required.

## Decision

An authenticated owner starts an APPROVED or ACTIVE Project TaskPlan through one execute-plan
Application API. Java optionally activates the plan and selects the first dependency-ready step in
sequence order. It creates a ProjectCodingJob with a stable plan/step/version idempotency key and
copies the reviewed execution binding into that job. A repeated command returns the same active job.

Project remains authoritative for lifecycle. Starting a Coding Job starts the exact PlanStep, Child
Task and Root Task. Successful reviewed completion completes the Step and Child Task, then creates
the next dependency-ready job in the same transaction. The final step completes the TaskPlan and
Root Task. A known failure fails the Step/Child/Root and cancels the plan. UNKNOWN, ambiguous or
lease-loss outcomes remain blocked and do not advance or retry an effect.

Dispatch is intentionally single-flight. Even when several DAG nodes are ready, only the earliest
sequence is materialized. Parallel Project steps require a separate decision for Agent assignment,
independent writable Workspaces and deterministic SourceMerge ordering. Manual per-step enqueue
remains compatible.

## Consequences

- No schema migration follows V1051; TaskPlan, Task, PlanStep and ProjectCodingJob remain the
  durable source of truth.
- A client supplies one reviewed binding for the plan, not individual lifecycle transitions.
- Completion and successor materialization are crash-atomic under the platform transaction.
- Project auto-dispatch never updates a remote Git ref and never bypasses Sandbox, Governance,
  Model/Tool ledgers, reviewer approval or local SourceMerge CAS.
- Parallel ready-step scheduling and automatic Agent assignment remain explicitly deferred.
