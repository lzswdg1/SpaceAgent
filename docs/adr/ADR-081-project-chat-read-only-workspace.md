# Project Chat read-only repository attachment

Project Chat may explicitly attach an existing READY managed Workspace using `workspaceId`
on `/api/v1/chat/messages` or `/messages/stream`. Runtime validates the current caller,
Conversation ProjectDirectory, source, Workspace and nonterminal Project Task through public APIs
before reserving a reply. The attachment is persisted before inference as a Runtime checkpoint
with phase `project-chat-workspace-v1`; its tenant, actor, Conversation, Project, directory,
Workspace and Task IDs remain fixed for the Run and survive restart/approval resume.

This is a read-only attachment, not PlanStep execution. Existing canonical writable Run/TaskPlan
invariants remain unchanged. Only configured `file_list` and `file_read` may use this attachment;
every execution revalidates live Project access, directory and Workspace state and the pinned
IDs. Other workspace tools cannot use this attachment, even if configured on the Agent.
Existing tool argument validation, path isolation, sandbox execution, ledger and UNKNOWN handling
remain in effect. No host path, credential or repository content is stored in the binding.

React prepares a repository conversation using public directory, Task, Conversation and Workspace
APIs. The user selects a READY source and explicitly enables the two Agent tools through the normal
configuration approval contract. A pending configuration must not be presented as enabled. The
browser submits a Workspace only from the selected directory and active Task; Java remains authority.
Existing default conversations are preserved when a source directory conversation is created.

Validation covers cross-directory/tenant denial, archived/terminal resources, exact binding on
tool execution, write-tool refusal, durable checkpoint lookup, and HTTP/SSE Workspace transport.
