# M8c-M8h - Compatibility Cutover and Legacy Retirement Runbook

> Historical runbook, retained as migration evidence only. Legacy Java runtime code is
> removed from the active tree and available only in a private archive not included in this public snapshot; these commands are not
> current rollback instructions.

Status: **M8c/M8d/M8e/M8f/M8g/M8h COMPLETE; M8 COMPLETE. M9 subsequently
completed; see `V2-FINAL-ACCEPTANCE.md`.**

Final Cutover evidence: `docs/architecture/M8-FINAL-CUTOVER-EVIDENCE.md`.
The platform database, Flyway migrations, ID-preserving backfill, watermarks, ID maps,
checksums, strict validation, write freeze, routing cutover, and runtime retirement are
complete. Legacy source remains rollback-only; source deletion is intentionally separate.

This runbook turns the M8a inventory and M8b migration map into controlled
subpasses. It does not delete legacy code yet.

## Preconditions

Before any legacy module is removed:

- M8a inventory is current.
- M8b migration map is current.
- `./mvnw -q -pl apps/platform-server -am test` passes.
- `./scripts/check-architecture.sh` passes.
- Relevant legacy service/backend tests pass while those modules exist.
- Docker/Testcontainers migration verification passes when Docker is available.
- `M9` remains `NOT_STARTED`.

## M8c - compatibility cutover

Status: COMPLETE

Goal: move remaining authoritative request paths behind `apps/platform-server`.
Legacy services may remain as thin adapters/proxies but must no longer own
business state.

For each state family:

1. Make the platform-server public Application API the only write path.
2. Convert legacy controllers/services into request adapters that call that
   public API, either in-process during the transition or over HTTP after the
   platform-server exposes the contract.
3. Remove transitional direct platform-server embedding only after the
   corresponding HTTP contract exists and is covered by a regression test.
4. Eliminate duplicate Flyway ownership, repositories, business services, and
   event ownership in the legacy module.

Do not remove a compatibility endpoint unless it is proven to be unused or
covered by the new platform-server contract.

### Core-loop scope guardrail

M8c proceeds in this fixed order:

1. Identity parity.
2. Agent parity.
3. Conversation/chat runtime correction.
4. Knowledge parity.

MCP, Skill, Automation, Governance, Artifact, and Observability are not migrated
until the four-phase core user loop is complete. M8d/M8e do not start during
these phases, and legacy services remain present.

### Phase 1 - Identity parity

Status: COMPLETE

- Platform HTTP owns register/login/refresh/logout/current-user compatibility.
- Refresh tokens are opaque, digested, durable, rotated once, and replay-safe.
- Logout durably revokes both the presented access token and optional refresh token.
- Personal-tenant OWNER membership is durable and is checked for every authenticated
  platform request after JWT validation.
- `V8__identity_sessions_and_memberships.sql` owns platform tenant memberships,
  refresh sessions, and access-token revocations.
- `PlatformIdentityHttpCompatibilityTest` covers the external contract and authorization
  boundaries.
- `PlatformIdentityPostgresIntegrationTest` is the skip-safe PostgreSQL durability gate.

### Remaining core phases

- Phase 2 Agent parity: COMPLETE.
- Phase 3 Chat runtime correction: COMPLETE.
- Phase 4 Knowledge parity: COMPLETE.

### Phase 2 - Agent parity

Status: COMPLETE

- Platform Application APIs own tenant/user-scoped Agent CRUD, configuration, runtime
  snapshots, provider/model references, Knowledge references, and API-key lifecycle.
- Agent calls only `InferenceApplicationApi` for provider/model validation and
  `KnowledgeOwnershipPort` for Knowledge ownership; provider execution and Knowledge data
  remain outside Agent.
- Existing `platform_agent_knowledge_bindings` is the normalized binding store.
- `V9__agent_keys_and_configuration.sql` adds only hashed Agent API-key persistence and
  binding backfill; raw keys are returned once and never stored.
- `PlatformAgentHttpController` remains an Application-API-only compatibility adapter.
- `PlatformAgentHttpCompatibilityTest` covers CRUD, isolation, provider and Knowledge
  binding, runtime configuration, and API-key create/verify/revoke behavior.
- `PlatformAgentPostgresIntegrationTest` is the skip-safe PostgreSQL durability gate.
- Legacy `services/agent-service` remains present and runnable.

### Core Platform Cutover Batch - Chat runtime and Knowledge

Status: COMPLETE

- `PlatformChatHttpController` delegates only to the runtime-owned chat Application API.
- Runtime owns AgentRun, ContextCompiler, Inference, Tooling, checkpoints, Conversation
  persistence, selective Memory evaluation, SSE runtime/tool events, and recovery.
- Platform production uses a real OpenAI-compatible inference infrastructure adapter;
  noop execution is explicit test/local configuration only.
- Knowledge owns document lifecycle, parsing, chunking, embedding requests/metadata,
  persistence, similarity retrieval, and storage references.
- Knowledge does not depend on Agent, Conversation, or Runtime. Agent-bound retrieval is
  performed through public Knowledge APIs.
- `V10__knowledge_processing_and_retrieval.sql` extends the existing Knowledge tables;
  no duplicate Document/Chunk tables were introduced.
- Chat and Knowledge compatibility tests and skip-safe PostgreSQL Knowledge coverage are
  present and green where the environment permits.

## M8d - legacy backend strangling

Status: COMPLETE

The legacy backend and four legacy core services install a conditional HTTP write guard.
Compose selects `legacy.write-mode=rollback-only`, allowing health/read inspection while
rejecting mutations with `410 LEGACY_WRITE_FROZEN`. Emergency write reactivation requires
an explicit operator override after platform writes stop; dual-write is unsupported.

For `backend/**`, retain only genuine required behavior. Do not blindly port:

- God classes;
- obsolete abstractions;
- duplicate repositories;
- fake memory/context behavior;
- in-memory durable state.

Each retained feature must land in one of the platform-server modules:
identity, agent, project, conversation, memory, knowledge, context, runtime,
inference, tooling, artifact, automation, integration, governance,
observability, shared.

## M8e - service retirement

Status: COMPLETE

Retire/remove obsolete Java service deployment modules where safe:

- `services/gateway-service`
- `services/identity-service`
- `services/agent-service`
- `services/chat-service`
- `services/knowledge-service`

Independent deployment is allowed only with a documented, measured reason:
security isolation, substantially independent scaling, fault isolation, or a
regulatory boundary. Existing service boundaries alone are not justification.

The default Compose service set is PostgreSQL/database-init/platform-server; no
gateway or retired core service is active. The default Maven reactor is shared-kernel +
platform-server. Legacy Java modules are available only through `-Plegacy-services`, and
legacy Compose services only through rollback profiles.

## M8f - shared-kernel cleanup

Status: COMPLETE

Reduce `shared/shared-kernel` to truly generic primitives only:

- API envelope/pagination/query primitives
- ID/time primitives
- generic exceptions/exception handler
- generic remote-call/resilience primitives
- generic crypto primitive only if truly reusable
- generic event publisher/audit contracts without business payloads

It must not contain business DTOs, service clients, business-domain events, or
persistence abstractions for business modules. Delete compatibility remnants
only after callers migrate.

Current cleanup candidates are documented in the M8a inventory and the M1/M2
plan notes. Business clients/DTOs/event payloads have moved to owners; final shell/JUnit
gates reject new business clients, event payloads, and persistence contracts in shared.

## M8g - data/cutover validation

Add and run validation proving:

- old IDs remain resolvable where required;
- conversation/message counts match;
- memory scope/state migration is correct;
- knowledge/document references remain valid;
- auth/user identity remains valid;
- Agent/Project/Task references remain valid;
- runtime/tool ledger state is not duplicated;
- no duplicate Flyway schema ownership remains.

Use:

```bash
./scripts/verify-m8-data-cutover.sh
./mvnw -q -pl apps/platform-server -am test
./scripts/check-architecture.sh
```

The database verifier is strict: `PLATFORM_DB_URL` is required unless `--static` is
explicitly used. Each optional legacy domain URL enables an independent count, ID-set,
and FK comparison. Any invariant or parity mismatch exits non-zero.

## M8h - legacy runtime retirement

Status: COMPLETE

Only after all previous M8 passes are green:

1. Prove no runtime caller remains.
2. Prove no required endpoint is missing.
3. Prove no unique data ownership remains.
4. Prove rollback/migration evidence exists.

Runtime retirement is complete without blanket source deletion. Retained source still has
rollback, migration-history, and compatibility-test value and is explicitly documented as
DEPRECATED/ROLLBACK_ONLY. Any later source deletion must occur in small, reviewable batches.
Do not use:

```text
git reset --hard
git clean
blanket rm of unrelated directories
```

Do not touch unrelated worktree changes unless an M8 backend compatibility requirement
absolutely requires a specific API contract change.

## Current M8 status

| Subpass | Status | Notes |
| --- | --- | --- |
| M8a inventory | COMPLETE | `docs/architecture/M8-LEGACY-INVENTORY.md` |
| M8b migration map | COMPLETE | `docs/architecture/M8-DATA-MIGRATION-MAP.md` |
| M8c cutover | COMPLETE | live JVM, direct platform routing, core HTTP/SSE smoke green |
| M8d backend strangling | COMPLETE | legacy write guard enabled by default |
| M8e service retirement | COMPLETE | legacy removed from default Compose/reactor/routing |
| M8f shared-kernel cleanup | COMPLETE | final caller audit and architecture gates green |
| M8g validation | COMPLETE | two VERIFIED runs; strict count/ID/checksum/FK validation passed |
| M8h runtime retirement | COMPLETE | runtime retired; rollback source intentionally retained |

Overall M8 is `COMPLETE`. M9 remains `NOT_STARTED`.
