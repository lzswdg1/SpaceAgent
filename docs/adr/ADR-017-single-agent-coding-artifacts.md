# ADR-017: Single-Agent Coding Loop and Acceptance Artifacts

- Status: Accepted
- Date: 2026-08-23
- Scope: M21-PR1

## Decision

- Runtime coordinates one AgentRun over one M20 READY managed Workspace.
- Every write, delete, check, diff, and commit-proposal operation is claimed/completed in
  ToolExecutionLedger before Runtime advances its checkpoint.
- Workspace paths are resolved by Project infrastructure, never accepted as absolute HTTP
  paths. Traversal/symlink escape and non-allowlisted executables fail closed.
- Artifact owns immutable PATCH, COMMIT_PROPOSAL, TEST_REPORT, and ACCEPTANCE_EVIDENCE
  metadata/content hashes. Large binary/object storage remains a reference boundary.
- Java may complete the Run/Task/PlanStep only when every Task acceptance criterion has
  PASS evidence and the Workspace produces a non-empty Patch/Commit proposal.

## Deferred

LOCAL Bridge coding execution, automatic LLM action generation, merge/reviewer policy,
and Multi-Agent coordination remain later increments. M21 proves the authoritative
single-Agent loop first.
