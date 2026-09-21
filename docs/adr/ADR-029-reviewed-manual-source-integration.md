# ADR-029: Reviewed Manual-First Source Integration

- Status: Accepted
- Date: 2026-08-23
- Scope: M29-PR1

## Context

M21 produces Patch and Commit Proposal Artifacts, M22 persists Agent Review decisions, M24
provides exact-operation Governance approvals, and Project owns isolated managed Git
worktrees/mirrors. The platform has no safe delivery transition connecting these owners.
Direct GitHub push or automatic remote merge would require new short-lived write credentials,
remote-side idempotency and conflict reconciliation that are not proven by the current MCP
contract.

## Decision

M29 introduces a Project-owned durable SourceMergeJob. Integration may create it only after
verifying an APPROVED AgentReview that contains the exact COMMIT_PROPOSAL Artifact and exact
Run/Project/Task/Workspace scope. Project verifies Workspace HEAD and Patch SHA-256, creates
one commit on the isolated branch, then waits in READY.

An explicit apply call consumes Java Governance `SOURCE_MERGE` authorization and updates
only the platform-managed default-branch ref using Git old-object compare-and-set. Drift is
CONFLICT and never triggers rebase, force update or automatic resolution. The result is
`APPLIED_LOCAL` with `remoteUpdated=false`; a user or trusted CI explicitly pushes or
cherry-picks it. Rollback is another explicit Governance-gated CAS from prepared Commit back
to the expected Base.

## Consequences

- The release candidate obtains a reviewed, auditable code-delivery closure without remote
  write credentials or an unsafe auto-merge claim.
- Artifact, Runtime, Governance and Project retain their existing authority boundaries.
- Remote PR/push/merge, Local Bridge merge and automatic conflict resolution remain future
  increments.
