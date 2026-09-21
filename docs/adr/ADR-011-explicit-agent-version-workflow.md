# ADR-011: Explicit AgentVersion Workflow

- Status: Accepted
- Date: 2026-08-22
- Scope: M15-PR2

## Context

Agent configuration is already stored in immutable AgentVersion snapshots and AgentRun
pins a version. Public Agent create/update currently publishes every runtime change
immediately. The product target requires an explicit lifecycle without breaking existing
clients or making historical Runs depend on the current configuration.

## Decision

- Add lifecycle states `DRAFT`, `IN_REVIEW`, `PUBLISHED`, and `DEPRECATED`.
- Draft configuration is immutable. A correction creates another draft instead of
  updating snapshot fields in place.
- Creating a draft copies the current PUBLISHED version and applies validated overrides.
  It does not change `currentAgentVersionId` or Agent-level Knowledge bindings.
- Review submission is `DRAFT -> IN_REVIEW`. Explicit publish requires `IN_REVIEW`,
  revalidates all external references, records the publisher/time, switches the Agent
  current pointer, and updates current Knowledge bindings in one transaction.
- Deprecation is terminal and may discard DRAFT/IN_REVIEW or retire a non-current
  PUBLISHED version. The current version cannot be deprecated.
- Rollback targets another PUBLISHED version and changes the current pointer only. It
  increments Agent revision and affects only future Runs; existing AgentRun pins remain.
- New AgentRuns must reject every non-PUBLISHED version, including direct internal calls.

## Compatibility

- Existing create/update endpoints continue their automatic PUBLISHED behavior.
- Existing PUBLISHED rows are backfilled with `published_by = created_by` and
  `published_at = created_at`; configuration hashes and version IDs do not change.
- The read-only AgentVersion API remains available to Runtime. Owner-scoped management
  uses a separate public Application API and repository-free HTTP endpoints.

## Deferred

- Separate reviewer/approver roles, review comments, scheduled activation, approval
  policy, and lifecycle audit-event tables are later Agent/Governance increments.
- Deprecated versions remain readable for historical Run reconstruction but cannot start
  a new Run or become a rollback target.
