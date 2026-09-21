# Project coding workbench

The Project product centers on conversations with an AI coding Agent. Repository files are an
optional inspector, alongside the actual source branch, workspace environment, commit and changes.
The user explicitly retained a distinct reviewer Agent. Coding submissions create an explicit
user-approved Project TaskPlan and enter the existing durable Project execution pipeline. They do
not remove review, Governance approvals, effect ledgers or UNKNOWN handling. Code inspection and
ordinary repository Q&A remain separately selectable.

The Project-owned workbench read API validates tenant/project membership before listing locally
available source branches, reading workspace status, or invoking bounded Sandbox file reads. File
inspection rejects traversal and Git metadata. React renders source as text and owns no filesystem
paths or Git credentials. Directory listing is bounded to 300 entries and file previews to 200k chars.

Switching the selected source branch prepares/selects a separate Workspace and changes the current
Conversation Task binding. Existing Workspaces and their uncommitted changes remain intact. Running
coding work must not be switched. Neither branch browsing nor switching performs remote Git writes.

New Git Workspaces use self-contained no-hardlinks local clones of the server-owned source mirror.
This is necessary because a linked worktree's .git pointer references paths outside the exact OCI
Workspace mount. The mirror still owns source refs. After reviewed commit preparation, Java stages
the exact prepared objects back into the local mirror without moving refs; existing Base-SHA CAS
still governs applying or rolling back a merge. Workers receive no mirror mount or remote credential.
Existing linked Workspaces remain readable; newly prepared coding Workspaces have isolated metadata.

Remote push and PR publication are not inferred from the reference screenshot: only real supported
backend operations are exposed. Existing reviewed local integration remains the delivery boundary.
