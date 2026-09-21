# ADR-004: Canonical AgentDefinition Identity

- Status: Accepted
- Date: 2026-08-22
- Scope: C1 Agent domain convergence
- Supersedes: ADR-003 sections that treated AgentConfiguration as a separate active aggregate

## Context

The active tree contained two unrelated identities. The early
`platform_agent_definitions` table and its Java Repository were used only by an isolated
control-plane test. Product CRUD, URLs, Conversation, Knowledge bindings, API keys,
AgentVersion, Chat, and AgentRun already used `platform_agent_configurations.id`.
There was no foreign key or data mapping that proved a relationship between the two IDs.

Keeping both models active made `AgentRun.agent_definition_id` misleading: the stored
value was the product Configuration ID. It also allowed mutable runtime fields to exist
beside the immutable AgentVersion snapshot introduced by ADR-003.

## Decision

The existing product-facing `platform_agent_configurations.id` is the canonical
`agentId`. It is promoted in place; no user receives a new ID and no URL changes.

The final aggregate is:

```text
AgentDefinition (stable agentId, tenant/owner, metadata, lifecycle, currentVersion)
  -> AgentVersion 1..N (immutable runtime snapshot)
AgentRun (agentId, pinned agentVersionId)
```

AgentDefinition contains only identity, ownership, name/description, ACTIVE/ARCHIVED
lifecycle, current AgentVersion ID, revision, and audit timestamps. Prompt, Provider,
Model, token/turn limits, tools, Knowledge, skills, and runtime policies belong only to
AgentVersion. Agent create publishes Version 1. A runtime-affecting update inserts the
next version; a metadata-only update leaves the current version unchanged.

## Compatible migration

V1005 renames the unused old table to `platform_legacy_agent_definitions`, renames the
product table to `platform_agent_definitions`, removes its duplicate mutable runtime
columns, and changes AgentVersion, Knowledge binding, API key, and Conversation foreign
keys to the canonical table with `ON DELETE RESTRICT`.

V1006 renames `platform_agent_runs.agent_definition_id` to `agent_id` and adds a
RESTRICT foreign key. Existing Conversation/Run constraints are `NOT VALID` so unknown
historical references remain readable; all new writes are checked. AgentVersion and
current-version foreign keys remain fully validated.

The old Definition rows have no provable mapping to public Agent IDs, so they are not
merged or deleted. They remain reference-only in `platform_legacy_agent_definitions`
until a separately authorized cleanup can prove retention/export conditions.

## Public compatibility

Agent REST paths and response fields remain unchanged, including the historical
`configVersion` response name, whose value now represents AgentDefinition revision.
Chat/SSE contracts are unchanged. Conversation, API keys, versions, runs, and Knowledge
bindings retain their existing agent IDs.

## Lifecycle and deferred work

Delete remains a soft archive and cannot cascade to versions, runs, conversations, API
keys, or bindings. AgentVersion remains PUBLISHED/DEPRECATED and immutable.

Explicit Draft/Publish approval and scheduled activation are future work.
ConversationAgentBinding and TaskAgentAssignment require separate models and migrations;
they are not implied by this convergence. Project/Task/Workspace and Multi-Agent are
also outside this decision.

## Consequences

- One production Agent domain aggregate, Application API, Ownership Port, and Repository.
- Runtime validates that a pinned AgentVersion belongs to the requested canonical Agent.
- Legacy Definition data is retained but has no active Java Repository or runtime path.
- Existing pre-V1004 runs can still have a null version and are not falsely reconstructed.
