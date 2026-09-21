# ADR-056: Chat TaskPlan proposals reuse Java Project authority

- Status: Accepted
- Date: 2026-09-06
- Milestone: M54-PR2

## Context

M54-PR1 gives every ordinary Chat request a durable Root Task but intentionally leaves TaskPlan
Project-only. The TypeScript LangGraph service can already return a strict `PLAN_PROPOSED` command,
yet Java only records that command as a RunEvent. Persisting plan text in Conversation, letting
TypeScript choose database identifiers, or creating a second ChatPlan store would each introduce a
second or weakened source of truth.

## Decision

Project continues to own the single TaskPlan/PlanStep aggregate, now with mutually exclusive scope:

- PROJECT plans retain Project membership, existing composite foreign keys and Coding behavior;
- CHAT plans carry tenant, owner, Conversation, Root Task, source AgentRun, proposal hash and
  strategy summary, while `projectId` is absent.

An accepted Chat `PLAN_PROPOSED` is converted by Runtime into a Project Application command. Java
validates exact Run/Root Task/Conversation ownership, published AgentVersion provenance, bounded
strategy and Step goals, unique Step keys and an acyclic dependency graph. It generates every
TaskPlan, Child Task and PlanStep identifier, persists normalized dependencies in one transaction,
and serializes duplicate source-Run acceptance with a PostgreSQL advisory transaction lock. The
same source Run and proposal hash replay the existing plan; changed evidence conflicts.

The owning user can read/list, approve, activate or cancel the proposal through Chat-scoped APIs.
Activation writes the Root Task current-plan pointer. No public endpoint accepts a raw plan
proposal, so a browser cannot impersonate the Planner boundary. Conversation remains a container;
TypeScript remains ephemeral and has no database, secret, Tool or lifecycle authority.

## Failure and cleanup

Malformed, oversized, cyclic, cross-owner or wrong-Root proposals fail before persistence. A
database failure rolls back generated Child Tasks and plan structure together. Conversation erasure
cascades Chat plans, steps and children using deferred same-statement foreign-key checks; Project
plans are unaffected.

M54-PR2 does not automatically invoke the Planner from ordinary Chat requests and does not execute
PlanSteps. M54-PR3A adds invocation and a durable review wait; M54-PR3B must add per-Step Model/Tool
execution evidence before the platform may claim automatic Chat plan execution.

## Consequences

The product now has one versioned TaskPlan model across Chat and Project without a hidden Project.
LangGraph supplies mature proposal reasoning while Java/PostgreSQL remains the only durable
authority. The M54-PR1 restriction that Chat Tasks cannot own a plan is superseded only by this
scoped, validated proposal path.
