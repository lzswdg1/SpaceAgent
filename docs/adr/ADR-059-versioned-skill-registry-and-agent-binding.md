# ADR-059: Versioned Skill Registry and Agent Binding

- Status: Accepted
- Date: 2026-09-07
- Milestone: M55-PR1

## Context

AgentVersion has carried `skillIds` since the early Agent schema, but the active Runtime catalog
has always rejected every Skill. Filesystem Codex Skills under `.agents/skills` are developer
workflow instructions and cannot become tenant business state. SpaceAgent needs a durable,
tenant-scoped Skill package before Chat or Project Runtime can safely use Skill instructions.

## Decision

Tooling owns stable Skill definitions and immutable numbered SkillVersion content. A version stores
bounded inert instructions, a normalized list of existing Runtime Tool IDs, a SHA-256 configuration
hash and creator/publish/deprecate evidence. PostgreSQL V1051 owns this authority.

Skill creation produces a DRAFT version. Only the creator may add, publish, deprecate or archive;
authenticated members of the same Organization may read the registry. Publishing a new version
deprecates the prior current version and atomically moves the definition pointer. User erasure
nulls historical actor references rather than deleting Organization-owned Skill evidence.

AgentVersion keeps its compatible `skillIds` field, but new snapshots interpret each value as the
exact current PUBLISHED SkillVersion ID. Draft, deprecated, archived, unknown and cross-tenant
references fail before an AgentVersion is persisted. An already-persisted AgentVersion remains an
immutable historical snapshot when a Skill later changes lifecycle.

## Safety boundary

A Skill is not code. M55-PR1 does not execute scripts, load packages, scan host files, grant a Tool
or MCP permission, or inject instructions into a model. Required Tool metadata can only narrow and
declare dependencies; the Agent's separately pinned Tool policy remains authoritative.

## Consequences

- Tooling gains `platform_skill_definitions` and `platform_skill_versions`.
- Agent depends only on the existing Tooling capability Application API.
- Existing Agents with no Skills are unchanged; there is no legacy Skill data to migrate.
- M55-PR2 now resolves exact version snapshots through the Tooling API, compiles them into bounded
  Chat/Project context and persists hash-only RunStep usage evidence without making Skill content
  an authorization source. See ADR-060.
