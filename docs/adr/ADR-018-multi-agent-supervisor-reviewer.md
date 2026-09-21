# ADR-018: TypeScript Supervisor/Subagents with Java Delegation Authority

- Status: Accepted
- Date: 2026-08-23
- Scope: M22-PR1

TypeScript LangGraph owns deterministic Supervisor/specialist/reviewer routing and emits
typed DELEGATE/HANDOFF/REVIEW proposals. Java validates every reference, persists
Delegation/Review/Handoff state, provisions a separate Workspace, starts the pinned child
Coding Run, and owns Artifact/acceptance approval. An approved Commit Proposal is only
merge input; M22 does not perform an automatic merge. LangGraph state and TypeScript
process memory are never recovery truth. A child Agent cannot receive the parent's
writable Workspace.
