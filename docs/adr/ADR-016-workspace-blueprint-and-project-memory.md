# ADR-016: Workspace Isolation, ProjectBlueprint, and Mid-term Memory

- Status: Accepted
- Date: 2026-08-23
- Scope: M20-PR1

## Decision

- Project owns Workspace metadata. Each active writable Workspace binds exactly one
  tenant, Project, Task, SourceRepository, base ref, branch, and random worktree key.
- PostgreSQL unique constraints forbid two active writable Workspaces for the same Task/
  Source and forbid reuse of an active worktree key.
- MANAGED worktrees are derived beneath `platform.workspace.managed-root`; no request may
  submit a server path. Git commands are allowlisted infrastructure operations with
  bounded output/timeouts and a minimal environment.
- LOCAL worktrees are materialized by the authenticated Bridge. Java emits a durable
  provisioning command containing only Bridge/root/source refs and branch/base data. The
  CLI maps rootHandle to its local path, creates the worktree, and returns only an opaque
  locator and Git HEAD.
- ProjectBlueprint is immutable/versioned structured PostgreSQL state. Confirmation
  supersedes the previous confirmed version and writes a Project-scoped mid-term Memory
  snapshot through the public Memory API.
- ProjectBlueprint/Task/Workspace remain authoritative; Memory is a searchable projection,
  never a replacement for structured state.

## Deferred

M20 does not implement the Coding Agent edit/test loop, Artifact/Patch/Commit proposal,
multi-Agent merge/reviewer, or worker lease/CAS. Those remain M21-M23.
