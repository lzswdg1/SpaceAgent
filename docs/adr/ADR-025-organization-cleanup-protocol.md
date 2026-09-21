# ADR-025: Empty Organization Cleanup Protocol

- Status: Accepted / implemented through M25-PR2C
- Date: 2026-08-23
- Scope: M25-PR2A (backend architecture audit)

## Context

The last active member leaving an Organization already suspends that Membership and marks
the Tenant/Organization DELETING. DELETING immediately rejects user access, but it does not
remove Organization resources or reach DELETED.

A direct tenant-row delete is incorrect:

- `platform_users.tenant_id` intentionally keeps the registration Organization as a
  historical primary reference while login may fall back to another active Membership;
- Runtime workers may hold leases and external Tool/Provider/Git effects;
- Artifact, Runtime, Conversation, Project, Agent and Inference have restrictive FKs;
- managed Git worktrees/mirrors live outside PostgreSQL;
- user-owned Knowledge and USER Memory are not Organization resources;
- Identity is forbidden from accessing another module's persistence implementation.

## Decision

### Authority and coordination

- Identity owns a durable CleanupJob/CleanupStep ledger and the Organization tombstone.
  The last-member leave transaction must enqueue the job atomically with DELETING.
- Integration owns the ordered coordinator and calls explicit public cleanup Application
  APIs exposed by Automation, Runtime, Artifact, Conversation, Memory, Project, Agent,
  Inference and Governance.
- Do not define a business-shaped cleanup SPI in shared. Do not make Inference depend on
  Identity or another business module. No participant may access a foreign repository.
- PostgreSQL owns claim, lease, fencing token, retry time, attempt count and completed-step
  evidence. Redis and process-local timers are wake-up aids only.

### Durable state machine

```text
PENDING -> CLAIMED -> RETRY
                   -> BLOCKED
                   -> COMPLETED
expired CLAIMED lease -> claimable again
```

Every owner step is idempotent. A step is recorded COMPLETED only after its owner transaction
and external deletion succeed. A crash before that record repeats the same idempotent step.
Error fields contain bounded safe codes/summaries, never Provider secrets, prompts, Tool
arguments/results or object capabilities.

### Required order

1. `AUTOMATION_FREEZE_PURGE`: prevent new occurrences and remove schedule/execution links.
2. `RUNTIME_QUIESCE`: cancel pending/claimed Continuations and non-terminal Runs, release
   fences, then defer until every previously issued lease has expired.
3. `ARTIFACT_PURGE`: delete Artifact metadata/object references before Run/Workspace FKs.
4. `RUNTIME_PURGE`: delete Delegation/Review then Runs; cascading RunStep/Checkpoint/Event/
   Recovery/Handoff/ToolLedger/ModelLedger/Lease/Continuation state follows.
5. `CONVERSATION_PURGE`: delete context snapshots and tenant Conversations/Messages.
6. `PROJECT_TASK_MEMORY_PURGE`: delete PROJECT/TASK scoped Memory while Project/Task IDs
   are still resolvable. USER Memory is retained.
7. `PROJECT_EXTERNAL_AND_DATABASE_PURGE`: idempotently remove managed worktrees/mirrors,
   then Bridge commands, Workspaces, Blueprints, Sources/connections, Plans/Steps/Tasks,
   Memberships and Projects.
8. `AGENT_PURGE`: delete keys/bindings/versions/definitions after all references are gone.
9. `INFERENCE_PURGE`: delete ModelPool members/pools, ProviderModels and encrypted Providers.
10. `GOVERNANCE_PURGE`: delete approvals and policy.
11. `IDENTITY_FINALIZE`: delete Organization refresh sessions, invitations and suspended
    memberships; set the minimal tenant tombstone DELETED; complete the cleanup job.

### Retention and blockers

- A configurable retention-not-before window separates access revocation from irreversible
  purge. Zero may be configured, but Runtime lease drain still applies.
- UNKNOWN Model/Tool effects remain safe evidence until the retention window ends. Cleanup
  records aggregate counts/hashes, never payloads, before purging their detailed rows.
- User/Profile/Credential, user-owned Knowledge, USER Memory, migration evidence, delivered
  audit evidence, CleanupJob/Step summaries and the minimal DELETED tombstone are retained.
- Current Artifact references are inline/ledger metadata. If an external object store is
  introduced, its owner must provide idempotent delete; absence of that adapter blocks the
  Artifact step.
- Managed Git is server-owned and must be removed. Local Bridge roots are client-owned;
  the server revokes the opaque capability and metadata but cannot claim deletion of local
  files it cannot address.

## Consequences

- M25-PR2 is split into a proven control plane before any purge participant is enabled.
- Organization deletion is auditable, resumable and active-active safe without making
  Identity a cross-module God service.
- The tenant row remains as a minimal DELETED tombstone because it anchors user history;
  “physical cleanup” means erasing Organization-owned resources and secrets, not corrupting
  the global user identity model.
- M25-PR2B implements the durable Job/Step, transactional enqueue, retention, claim lease/
  fencing, heartbeat, ordered Step evidence and bounded retry control plane. It intentionally
  had no scheduler or purge participant at that milestone boundary; DELETING was therefore
  still the last implemented state in PR2B.
- M25-PR2C implements every current owner API, PostgreSQL-only scheduler, Runtime quiesce/
  lease drain, managed Git cleanup, ordered database purge and final DELETED Tombstone.
  Current inline/ledger Artifact references require no external object delete. A future
  S3/MinIO adapter must extend the Artifact owner step before such storage is enabled.
