# ADR-060: Pinned Skill Context and Runtime Evidence

- Status: Accepted
- Date: 2026-09-07
- Milestone: M55-PR2

## Context

M55-PR1 made SkillVersion durable and allowed AgentVersion to pin the exact current published
version, but no runtime consumed the instructions. Resolving a mutable current Skill at execution
would break AgentVersion immutability, while treating Skill metadata as permission would bypass the
existing Agent/Governance/Tool ledger boundary.

## Decision

The Tooling capability Application API exposes two distinct operations:

- new Agent snapshots validate only current PUBLISHED SkillVersion IDs;
- an existing pinned AgentVersion resolves the exact previously published version, including after
  that version is DEPRECATED or its Skill definition is archived.

Both operations require every declared Tool to be separately enabled on the AgentVersion. Skill
metadata can therefore narrow or reject execution but can never grant a Tool, MCP, network,
Workspace or Sandbox capability.

Chat ContextCompiler receives Skill contributions below the Agent system prompt and above
Conversation/Knowledge/Memory evidence. It fails closed when every pinned Skill cannot fit the
context budget. The automatic Chat planner receives bounded Skill sources; reviewed Chat PlanSteps
and final synthesis use the same compiled package. Project Coding adds the exact Skill messages to
each main-Agent iteration and Reviewer call without persisting a mutable current lookup.

Before each affected model RunStep, Runtime appends a `skill-context-bound` checkpoint containing
only SkillVersion IDs and configuration hashes. Raw instructions and Skill names are excluded from
that evidence and from telemetry. Approval/UNKNOWN checkpoints may still contain the bounded model
context required for exact same-Run continuation; they are private recovery state, not telemetry.

## Consequences

- No schema migration follows V1051.
- Skill instruction changes require a new SkillVersion and then a new AgentVersion.
- Existing pinned Agents survive Skill deprecation/archive; new Agent snapshots cannot select the
  historical version.
- Missing tenant visibility, required Tool enablement, total Skill limits or Chat token budget fail
  before model/tool execution.
