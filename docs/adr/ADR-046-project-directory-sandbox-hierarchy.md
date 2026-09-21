# ADR-046: ProjectDirectory is the Project-mode execution boundary

- Status: Accepted
- Date: 2026-09-05
- Milestone: M51-PR1

## Context

The existing Project backend persisted Projects, SourceRepositories, Conversations, Tasks,
Workspaces and Coding Runs, but Conversations were attached directly to a Project and a Coding Run
did not durably identify its Workspace. That was insufficient for a product hierarchy where one
Project contains multiple imported project directories and each directory contains its own
Conversations. It also allowed a caller to present another same-Project Workspace on a later Coding
request because only Task membership was checked.

## Decision

Project owns `ProjectDirectory` as a logical, tenant-scoped source root or bounded subdirectory.
The product hierarchy is:

```text
Project -> ProjectDirectory -> Conversation -> Task/TaskPlan/AgentRun
                         \-> SourceRepository -> Workspace -> Sandbox
```

Every new Project receives one default logical directory. A SourceRepository may have source-root
and organizational subdirectories, but M51-PR1 permits only the source root (`.`) to bind an
executable Workspace. A ProjectDirectory stores a normalized relative path only; it never stores a
client absolute path or resolves a browser-supplied server path.

Every new Project Conversation resolves exactly one active ProjectDirectory. Every production
Workspace resolves a directory belonging to the same Project, tenant and SourceRepository. Every
new Coding Run immutably pins both `project_directory_id` and `workspace_id`. Coding start proves
that Conversation and Workspace share the directory, and later actions reject any different
Workspace or directory.

The Sandbox boundary separates control from effects. Java/PostgreSQL owns identity, authorization,
Provider/MCP secrets, plans, ledgers and checkpoints. Project bytes, Git and command effects must
converge on the exact directory-bound Workspace/Sandbox. No model or MCP secret is placed in the
Sandbox. M51-PR1 establishes the durable scope; moving the remaining host-side read/write/document/
Git transports behind the Sandbox is M51-PR4.

## Migration and compatibility

V1043 creates a default directory for every existing Project and a source-root directory for every
existing SourceRepository. Existing Project Conversations are attached to the default directory;
existing Workspaces are attached to their matching source root. Existing Coding Runs are backfilled
from checkpoint Workspace evidence when possible. Legacy unbound rows remain readable, but new
production Coding starts require the strict binding.

## Consequences

- Conversation remains the owner of messages, not an orchestrator or Project container.
- ProjectDirectory and Workspace metadata remain Project-owned; Runtime stores only immutable
  foreign references needed to fence execution and recovery.
- Multiple Conversations can safely group under one directory, while parallel writable tasks still
  use distinct Workspaces/worktrees.
- Local Bridge checkout remains supported, but trusted local Sandbox execution is a later step; the
  server does not pretend that an opaque client locator is a server-mounted directory.
