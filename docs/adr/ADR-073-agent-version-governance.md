# ADR-073: AgentVersion review and activation governance

- Status: Accepted
- Date: 2026-09-09
- Milestone: M63-PR1

## Context

Agent owns immutable AgentVersion snapshots and currently allows the owning user to submit, review and publish a
version through one lifecycle. The target product needs independent review comments, approval, scheduled
activation/deprecation/rollback and an explicit automatic-activation policy without changing already pinned Runs.

## Decision

- KEEP immutable AgentVersion configuration and Runtime's exact AgentVersion pin. Publishing or rollback changes
  only the AgentDefinition current-version pointer for future Runs.
- SPLIT governance from execution records. Governance owns the Organization policy; Agent owns review threads,
  comments, decisions and activation schedules. Identity remains the authority for active Organization membership.
- Production defaults require review and three distinct actors: creator, reviewer and approver. Identity role checks
  occur through public APIs; actor IDs are immutable audit references, not new credential or membership state.
- A review binds the exact AgentVersion ID and config hash. Any changed hash, stale/expired review, missing approval
  or actor-separation violation fails closed.
- Automatic activation is disabled by default and requires an explicit revisioned Organization policy. Enabling it
  never permits model/self approval: only an already independently approved, exact-hash version may be activated by
  the scheduled worker.
- U02 adds Agent-owned immutable review/comment/decision persistence plus Governance policy persistence. U03 adds
  activation/deprecation/rollback schedules with PostgreSQL clock, claim, lease and fence. U04 wires application and
  worker behavior; U05 adds owner HTTP, redacted Admin projection, cleanup and final gates.

## Failure and compatibility semantics

Current public publish behavior remains unchanged during U01-U03. U04 atomically introduces policy evaluation at
the publish boundary after durable review/schedule evidence exists. Existing AgentVersion rows remain immutable;
no migration fabricates reviewers, approvals or historical comments. Scheduled activation UNKNOWN remains blocked
and never advances the current-version pointer blindly.

## Consequences

U01 defined framework-independent Governance policy/API and the ownership contract. U02 adds V1064/76 with
Governance policy CAS and Agent-owned exact-hash review threads, hash-verified comments and append-only review/
publication decisions. U03 adds V1065/77 lease-fenced schedules; U04 adds policy/review application services,
approved-review lifecycle gates and the stateless schedule worker. Runtime pins remain immutable and ambiguous
activation becomes UNKNOWN. U05 adds authenticated tenant APIs, count-only redacted SystemAdmin evidence and
FK-ordered Agent/Governance cleanup. Frontend and live external operations remain excluded.
