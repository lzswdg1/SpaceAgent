# M8b - Authoritative Data Ownership Migration Map

> Historical migration evidence. Legacy source paths refer to a private archive not included in this public snapshot;
> the code is removed from the active tree and is not a supported rollback runtime.

Status: M8b COMPLETE (map only; no legacy data was deleted)

This map defines a single-authority cutover for every durable state family that
currently exists in the legacy monolith or transitional services. It follows
the M8b rules:

- Never create two permanent authoritative copies.
- Where migration is required, make it idempotent and resumable.
- Preserve IDs where referenced by other rows.
- Verify row counts, checksums, and invariants.
- Document transactional boundaries.
- Do not silently drop legacy data.

The platform-server is the final Java authority. Legacy tables may remain
readable during the cutover, but writes must be disabled at the service boundary
before final deletion.

## Legend

| Column | Meaning |
| --- | --- |
| Legacy table/state | Current physical owner and table/key/path |
| Target module | Platform-server logical module that owns the migrated state |
| Target table/state | Platform-server table or state primitive |
| Migration/backfill method | Idempotent/resumable cutover strategy |
| Verification query | Query or invariant proving row/checksum parity |
| Rollback strategy | How to return to the legacy source without losing writes |

## Identity / user / auth data

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `identity-service.users` | identity | `platform_users` | Upsert by legacy `id`, preserving ID; map username/email/password hash/status/tenant fields | `SELECT count(*) FROM platform_users WHERE id = legacy_id` | Re-enable legacy identity-service; platform users are write-disabled or dual-read only |
| `backend.users` | identity | `platform_users` | Backfill into the same target after reconciling identity-service winners; preserve canonical user ID | `SELECT count(*), sum((username IS NOT NULL)::int) FROM platform_users` | Use an `id_map` backfill table to remap old IDs |
| `identity-service.user_profiles` | identity | `platform_user_profiles` | Upsert by `user_id`, preserving ID; map tone/timezone/summary/config-version | `SELECT count(*) FROM platform_user_profiles WHERE user_id IN (legacy user ids)` | Snapshot legacy profile rows before cutover |
| `backend.user_profiles` | identity | `platform_user_profiles` | Merge profile fields only where identity-service profile is absent; do not overwrite newer config-version | `SELECT count(*) FROM platform_user_profiles WHERE user_id = legacy_user_id` | Restore from migration snapshot |
| `identity-service.refresh_tokens` | identity | `platform_refresh_tokens` (or identity module equivalent) | Preserve token digest/session metadata; never migrate plaintext refresh token if only digest is stored | `SELECT count(*) FROM platform_refresh_tokens WHERE legacy_token_id = ...` | Preserve legacy refresh table until token TTL expires |
| `backend.api_key` / `agent-service.agent_api_keys` | identity/agent | `platform_agent_api_keys` / identity API-key state | Preserve key ID and hash; migrate enabled/expiry/owner/tenant | `SELECT count(*) FROM platform_agent_api_keys WHERE id = legacy_key_id` | Keep legacy API-key table read-only until verification |
| tenant membership/invitations (`tenants`, `tenant_memberships`, `tenant_invitations`, `tenant_audit_logs`, `tenant_outbox_events`) | identity | platform identity equivalents | Upsert tenants/memberships by ID; migrate open invitations and audit history; outbox is replayed from platform identity | `SELECT count(*) FROM platform_identity_tenant_* WHERE legacy_id = ...` | Pause tenant writes in legacy service, replay outbox on rollback |

## Agent definitions/config

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.agent_configs` | agent | `platform_agent_definitions` + `platform_agent_configurations` | Split legacy config into immutable definition and versioned configuration; preserve agent ID and config-version | `SELECT count(*) FROM platform_agent_configurations WHERE id = legacy_id AND config_version = legacy_version` | Keep legacy agent-config table read-only |
| `agent-service.agent_configs` | agent | same | Service is current authority; upsert by `id`, preserve `config_version` and owner/tenant | `SELECT count(*), max(config_version) FROM platform_agent_configurations GROUP BY id` | Legacy agent-service can resume until platform endpoint verified |
| `agent-service.agent_knowledge_bases` | agent | `platform_agent_knowledge_bindings` | Preserve binding ID and `(agent_id, knowledge_base_id)` | `SELECT count(*) FROM platform_agent_knowledge_bindings WHERE id = legacy_id` | Keep legacy bindings table |
| `backend.agent_templates` | agent | platform agent template table | Migrate template body and version; preserve ID | `SELECT count(*) FROM platform_agent_templates WHERE id = legacy_id` | Keep legacy template table read-only |

## Model/provider config

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.model_providers` / `agent-service.model_providers` | inference | `platform_model_providers` | Upsert by ID; preserve owner/tenant/base-url/type/default flags; migrate encrypted secret as opaque ciphertext, never plaintext | `SELECT count(*) FROM platform_model_providers WHERE id = legacy_id AND has_secret` | Keep legacy provider table read-only |
| `backend.provider_models` / `agent-service.provider_models` | inference | `platform_provider_models` | Preserve `(provider_id, model_id)`; migrate model metadata/token limit | `SELECT count(*) FROM platform_provider_models WHERE provider_id = legacy_provider_id AND model_id = legacy_model_id` | Keep legacy provider/model rows |
| `backend.agent_usage_records` / `chat-service.chat_turn_usages` | observability | platform usage tables | Migrate usage ledger as immutable records; preserve IDs where referenced | `SELECT count(*), sum(total_tokens) FROM platform_usage_records` | Keep legacy usage tables until platform observability is authoritative |

## Conversations/messages

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.conversations` | conversation | `platform_conversations` | Upsert by ID; preserve owner/agent binding/status/timestamps | `SELECT count(*) FROM platform_conversations WHERE id = legacy_id` | Keep legacy conversations table read-only |
| `backend.messages` | conversation | `platform_messages` | Preserve message ID and conversation ID; map role/content/tool-call/usage/sequence | `SELECT count(*), sum(content IS NOT NULL)::int FROM platform_messages WHERE conversation_id = legacy_conversation_id` | Keep legacy messages table |
| `chat-service.conversations` | conversation | `platform_conversations` | Current chat-service is transitional authority; upsert by ID, then compare counts with backend | `SELECT count(*) FROM platform_conversations; SELECT count(*) FROM chat_service.conversations` | Re-enable chat-service write path |
| `chat-service.chat_messages` | conversation | `platform_messages` | Preserve message IDs; map legacy `chat_messages` to platform messages | `SELECT count(*) FROM platform_messages WHERE conversation_id IN (legacy conversation ids)` | Keep legacy chat_messages until parity |
| `chat-service.chat_turns`, `chat_turn_usages` | observability/runtime | platform run/usage tables | Rebuild turns from durable AgentRun/Checkpoint; usage rows become immutable ledger rows | `SELECT count(*) FROM platform_agent_runs WHERE conversation_id = ...; SELECT count(*) FROM platform_tool_execution_ledger` | Preserve legacy turns as read-only history |
| `chat-service.conversation_context_summaries` | conversation | `platform_conversation_context_snapshots` | Preserve snapshot ID/version/range/checksum; upsert by `conversation_id/version` | `SELECT count(*), max(version) FROM platform_conversation_context_snapshots GROUP BY conversation_id` | Keep legacy summaries read-only |

## Memory

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.memory_items` | memory | `platform_memory_candidates` + `platform_consolidated_memories` | Classify each legacy item as candidate or consolidated by scope/kind; preserve ID and scope/owner/task/conversation references | `SELECT count(*) FROM platform_memory_candidates WHERE legacy_id = ...; SELECT count(*) FROM platform_consolidated_memories WHERE legacy_id = ...` | Keep legacy memory_items read-only |
| `chat-service.memory_entries` | memory | same | Current chat-service memory entries are candidates; migrate to `platform_memory_candidates`, preserving scope | `SELECT count(*) FROM platform_memory_candidates WHERE conversation_id = legacy_conversation_id` | Keep legacy memory_entries until candidate lifecycle is verified |

## Knowledge/document metadata

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.knowledge_documents` | knowledge | `platform_knowledge_documents` | Upsert by ID; preserve owner/name/mime/status/storage reference | `SELECT count(*) FROM platform_knowledge_documents WHERE id = legacy_id` | Keep legacy knowledge tables read-only |
| `backend.knowledge_chunks` | knowledge | `platform_knowledge_chunks` | Preserve chunk ID/document ID/embedding/content hash/order | `SELECT count(*), sum(embedding IS NOT NULL)::int FROM platform_knowledge_chunks WHERE document_id = legacy_document_id` | Keep legacy chunks |
| `knowledge-service.knowledge_documents` | knowledge | `platform_knowledge_documents` | Current service authority; upsert by ID, preserve object key and status | `SELECT count(*) FROM platform_knowledge_documents WHERE id = legacy_id` | Re-enable knowledge-service ingestion |
| `knowledge-service.knowledge_chunks` | knowledge | `platform_knowledge_chunks` | Preserve chunk ID and document ID; validate embedding dimensions | `SELECT count(*), avg(array_length(embedding,1)) FROM platform_knowledge_chunks` | Keep legacy chunks |

## Runtime state

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| in-memory session registry/event buffer | runtime/integration | `platform_agent_runs`, `platform_run_steps`, `platform_run_checkpoints` | Rebuild durable run state from conversation/run ledger; in-memory state is not migrated directly | `SELECT count(*) FROM platform_agent_runs WHERE conversation_id = ...` | Restart legacy session path; in-memory state cannot be rolled back |
| `backend.agent_trace`, `trace_span` | observability/runtime | platform trace tables | Migrate trace/span as immutable records; preserve trace ID/span ID | `SELECT count(*) FROM platform_trace WHERE legacy_trace_id = ...` | Keep legacy trace tables read-only |
| `platform_agent_runs`, `platform_run_steps`, `platform_run_checkpoints`, `platform_run_recoveries`, `platform_run_handoffs` | runtime | already platform-owned | Already authoritative; no migration required | `SELECT count(*) FROM platform_agent_runs` | N/A |

## Tooling ledger

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `platform_tool_execution_ledger` | tooling | already platform-owned | Already authoritative; legacy chat MCP audit rows must not become a second authoritative ledger | `SELECT count(*) FROM platform_tool_execution_ledger` | N/A |
| `chat-service.mcp_tool_audit_logs` | tooling/observability | platform tool/audit tables | Migrate as audit history only; do not use as execution authority | `SELECT count(*) FROM platform_mcp_audit WHERE legacy_id = ...` | Keep legacy audit read-only |
| `chat-service.mcp_server_configs` | tooling | platform MCP config table | Upsert by ID; preserve encrypted secret and transport config | `SELECT count(*) FROM platform_mcp_configs WHERE id = legacy_id` | Keep legacy MCP configs |

## Uploaded files/artifact references

| Legacy table/state | Target module | Target table/state | Migration/backfill method | Verification query | Rollback strategy |
| --- | --- | --- | --- | --- | --- |
| `backend.uploaded_files` | artifact | platform artifact table | Preserve file ID, storage path, owner, checksum, and original metadata | `SELECT count(*) FROM platform_artifacts WHERE id = legacy_id` | Keep legacy uploaded_files table and object |
| `FILE_LOCAL_BASE_PATH` and temp-file server | artifact | platform artifact storage | Move object bytes or re-point storage path; preserve object key invariants | object checksum comparison | Restore old path |
| `knowledge-service` local/S3 object keys | knowledge | platform knowledge storage | Keep object key and bucket; only move the owner/tenant reference, not bytes unless storage migrates | `SELECT object_key FROM platform_knowledge_documents WHERE id = legacy_id` | Point knowledge storage back to old bucket/path |

## Transactional boundaries

Each state family is migrated in a single logical transaction unless its row
count exceeds a practical batch limit. Batch migrations use:

1. `SELECT` source rows in a stable order;
2. an idempotent `INSERT ... ON CONFLICT (id) DO UPDATE` or equivalent
   platform upsert;
3. a batch watermark/checkpoint table for resumability;
4. a post-batch verification query before committing the next batch.

Legacy tables remain readable during backfill and are switched to read-only
before final deletion.

## Rollback strategy

Before cutover:

1. Snapshot legacy table counts and checksums.
2. Keep legacy services deployable until platform-server endpoint parity is
   proven.
3. Write only to platform-server once cutover is active; do not run dual writes
   for long periods.
4. On rollback, stop platform-server writes, re-enable the legacy service
   write path, and replay any platform-originated rows created after the
   cutover from the migration watermark.

## Permanent authoritative copies

The platform-server tables are the only intended permanent authoritative copy.
Any legacy table retained after cutover is retained only as a documented,
read-only history/audit source or transitional compatibility adapter, never as
a second live authority.
