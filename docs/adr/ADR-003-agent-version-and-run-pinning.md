# ADR-003: Immutable AgentVersion and AgentRun Pinning

- Status: Accepted
- Date: 2026-08-22
- Scope: M10-PR3 Agent runtime configuration versioning

## Context

The public Agent CRUD contract uses `platform_agent_configurations.id` as `agentId`.
`platform_agent_definitions` is a separate earlier model and has no reliable foreign-key
or product relationship to AgentConfiguration. The Chat runtime historically stored the
AgentConfiguration ID in `platform_agent_runs.agent_definition_id`, despite that column's
older name, and loaded mutable configuration immediately before starting a run.

M10-PR3 does not merge AgentDefinition and AgentConfiguration. Doing so would change the
public Agent identity and expand this milestone beyond versioning. AgentVersion therefore
belongs to the product-facing AgentConfiguration. The naming/semantic convergence of the
two Agent models remains explicit architecture debt.

## Decision

`platform_agent_versions` stores immutable, typed runtime snapshots. Creation inserts
Version 1 and makes it current in the same application transaction. A runtime-affecting
Agent update locks the AgentConfiguration row, updates the mutable current configuration,
inserts the next PUBLISHED version, and changes `current_agent_version_id` atomically.
Name or description-only updates do not publish a version. Repeating the same effective
configuration also leaves the current version unchanged.

There is no DRAFT state or public publish/deprecate endpoint in this milestone. Existing
create/update behavior remains an automatic-publish compatibility strategy. The domain
supports the only permitted state transition, PUBLISHED to DEPRECATED; it cannot restore
a deprecated version or mutate a snapshot.

## Snapshot boundary

Each version types and persists all currently effective runtime fields:

- System Prompt and Provider/Model references;
- Temperature, context/output token limits, and max turns;
- Permission, Memory, RAG, and Network behavior;
- normalized Knowledge, Tool, and Skill IDs;
- source mutable config version and audit identity/time.

Provider API keys, Authorization headers, encrypted/decrypted Provider secrets, name,
description, timestamps, and other non-runtime presentation fields are excluded from the
configuration hash. Credentials continue to be resolved by the Inference module at call
time; credential lifecycle is not versioned by this PR.

## config_hash

`config_hash` is lowercase SHA-256 over a fixed-order canonical representation of the
effective typed fields. Text values use JSON escaping, nullable Provider/Model references
use an explicit `null`, temperature uses a stable decimal representation, and ID arrays
are trimmed, deduplicated, and sorted. The V12 backfill and Java snapshot factory use the
same canonical representation, preventing a no-op update from publishing a false Version
2 after migration.

## Run pinning

Every new `StartAgentRunCommand` requires `agentVersionId`. Chat resolves the current
version, loads its runtime projection through the Agent module API, and then creates the
run. `platform_agent_runs.agent_version_id`, `AgentRunView`, and RecoveryResumeState keep
that ID for the lifetime of the run. Runtime never accesses AgentVersion persistence
directly.

Updating an Agent therefore affects only later runs. An existing run continues to resolve
the version it pinned, including Prompt, Provider/Model, Knowledge/Tool/Skill lists, and
token limits. ModelCallLedger hashing naturally observes the actual pinned request; its
claim and fencing semantics are unchanged.

## Existing data and Legacy Unversioned runs

The platform-server and runtime locations share one Flyway schema history that was
already at V1002. Therefore the globally valid migrations are V1003 for AgentVersion and
V1004 for AgentRun pinning; a nominal V12 would be an ignored out-of-order migration on
an existing database.

V1003 creates Version 1 for every existing AgentConfiguration and sets it current. The
snapshot comes from the effective configuration and normalized Knowledge bindings. It
does not invent a Provider or Model and does not store credentials.

V1004 deliberately leaves existing AgentRun rows with `agent_version_id = NULL`. Those
runs are LEGACY_UNVERSIONED: their historical effective configuration cannot be proven,
so binding them to the current version would fabricate reproducibility. They remain
readable and recoverable as legacy records, but the system does not claim exact replay.

## Deletion and immutability

Agent delete is a soft archive of the mutable AgentConfiguration. Versions and referencing
runs remain. Foreign keys use RESTRICT rather than cascade. AgentVersion Repository has
insert and status-deprecate operations only; it exposes no snapshot update or delete API.

## Consequences and deferred work

- Public Agent, Chat, and SSE DTOs/endpoints remain unchanged.
- Explicit Draft/Publish approval and scheduled activation remain future work.
- AgentDefinition/AgentConfiguration semantic convergence requires a separate migration.
- ConversationAgentBinding and TaskAgentAssignment are not implemented here.
- Project/Task/Workspace, ModelPool/Fallback, Multi-Agent, and automatic recovery
  continuation remain outside this decision.
