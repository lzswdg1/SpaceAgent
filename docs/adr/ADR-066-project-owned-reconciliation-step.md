# ADR-066: Project-Owned Reconciliation Step Lifecycle

- Status: Accepted
- Date: 2026-09-08
- Milestone: M58-PR2-U05A
- Owner: Project

## Context

`TaskPlan` and its business `PlanStep` structure are immutable after insertion. A SourceMerge base
drift must preserve the original Patch, Commit, Test, Review, SourceMerge and merge-barrier evidence,
but Runtime cannot create a Project Step or use its own blocker as business authority.

## Decision

Project owns a distinct `ProjectReconciliationStep` aggregate. It is not appended to, substituted for,
or otherwise written into `TaskPlan.steps`; the original TaskPlan stays ACTIVE and the original PlanStep
remains unchanged. A successor TaskPlan is reserved for a business requirement or plan change, not a
normal SourceMerge conflict.

On base drift, Integration calls the Project public Application API to create a reconciliation Step. The
aggregate immutably binds the original tenant, owner, project, directory, taskPlan, planStep, execution,
barrier, SourceMerge, expected and actual Base SHA, and Patch/Commit/Test/Review references. The initial
state is `WAITING_RECONCILIATION`.

A resolution proposal must bind a distinct isolated Workspace and the current actual Base SHA. The later
adapter must keep every file and Git operation inside OCI Sandbox, Governance and Tool Ledger boundaries.
Force, automatic rebase, remote push and replacing original Artifacts are prohibited.

Only a Project service that has verified new Patch, Commit, Test, Review and SourceMerge CAS evidence may
mark the reconciliation Step `RESOLVED`. UNKNOWN Git effects transition to `BLOCKED` and cannot be
blindly retried. Runtime may project the wait/blocker and later resume the original barrier/execution only
through an explicit reconciliation API; it never owns or fabricates the Project aggregate.

## Consequences

M58-PR2-U05A defines the framework-independent Project domain and public contract only. U05B adds
forward-only persistence and repository CAS. U05C wires Runtime, Integration and Sandbox behavior and
proves the local Git conflict path. No TaskPlan mutation, Flyway change, external operation or frontend
change is introduced by this unit.

## M65-PR4 crash-recovery amendment

Runtime atomically commits a completed Project Coding Job and its immutable Barrier Entry in one short
PostgreSQL transaction. Integration never performs Git/SourceMerge apply inside that transaction. A separate
bounded drain Worker claims only the current stable-order Entry with a durable attempt, claim token, fencing
token and lease. Expired claims may be taken over, but stale workers cannot heartbeat, release, block or advance.

SourceMerge remains the effect authority. A reclaimed Barrier claim may call apply only while SourceMerge is
provably `READY`. `APPLYING` or `UNKNOWN` must first use Project's reconciliation/inspection API; an applied
postcondition finalizes the same Entry, an unchanged Base releases for a known-safe retry, drift creates or
replays the Project reconciliation Step, and an inconclusive result blocks as `UNKNOWN`. Barrier cursor advance
is last, after idempotent Step/Run/handoff/successor finalization, so crashes before or after Git apply remain
recoverable without overwriting Artifacts, force, rebase or remote push.
