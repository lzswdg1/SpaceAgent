# SpaceAgent V2 Final Acceptance

Date: 2026-08-20

Status: **M9 COMPLETE — V2 REFACTOR COMPLETE**

L0 follow-up: the deprecated Java runtime sources were removed from the active tree and
archived outside this public snapshot. Legacy Runtime Rollback is no longer supported.

M38-PR1/PR2 follow-up: Redis/Kafka and retired shared infrastructure were removed;
Python AI orchestration, native GitHub OAuth/API, host-process Sandbox execution and
`PlatformControlPlaneConfiguration` were also retired. Historical gate counts below remain
M9 evidence, not the current test inventory.

## Architecture acceptance

- `apps/platform-server` is the only active Java business backend.
- Default Maven modules are `shared/shared-kernel` and `apps/platform-server`.
- Default Compose services are `postgres`, `database-init`, and `platform-server`;
  legacy gateway/backend/core services are absent.
- PostgreSQL `spaceagent_platform` owns durable business, AgentRun, Checkpoint,
  Conversation snapshot, and ToolExecutionLedger state. Redis is cache/ephemeral only.
- AgentDefinition owns no Project/Task/Workspace/Conversation/AgentRun/Checkpoint state.
- Conversation, Memory, Knowledge, and Project do not depend on Runtime. Inference does
  not depend on any other business module.
- Controllers use public Application APIs and do not import repositories/persistence.
- Domain code has no Spring/MyBatis/LangChain4j/LangGraph/Temporal/provider dependency.
- The Python OCI Sandbox Worker imports no business persistence client and is not a source of truth.

Executable enforcement: 11 platform architecture tests, 3 shared-kernel architecture
tests, and 19 shell violation gates.

## Final dependency/dead-code audit

Maven dependency analysis was reviewed with Spring starter/reflection false positives.
Seven interfaces with only their own declaration, no caller, no implementation, and no
rollback value were removed: obsolete Context/Project/Tool ownership/store scaffolding.
M38-PR2 later removed `PlatformControlPlaneConfiguration` and the remaining compatibility
adapters after their replacements passed. No duplicate active persistence or Runtime
execution path remains.

## Security acceptance

- JWT signature/expiry validation, tenant membership authorization, access revocation,
  refresh rotation/replay rejection, and logout are covered.
- Passwords use BCrypt; refresh/access tokens and Agent API keys persist only SHA-256
  digests. Agent raw keys are returned once and revocation is enforced.
- Provider secrets use versioned AES-GCM ciphertext and are never exposed by query APIs.
- Provider Base URLs now require HTTPS and a configured exact/wildcard host allowlist;
  loopback HTTP requires an explicit local-development flag.
- PostgreSQL mode fails fast on known development/placeholder JWT, internal-token,
  provider-encryption, and database secrets. It rejects noop inference and deterministic
  embedding. The local migration override is explicit and logs a warning.
- Internal endpoints require the constant-time checked internal token. Public/internal
  route rules and unauthorized rejection passed live smoke.
- Cross-origin browser access is not enabled by default; production uses same-origin
  routing/reverse proxying. No permissive wildcard CORS credential policy is present.
- Spring's unused generated default user is disabled. SSE async/error redispatch is
  permitted only after the authenticated request dispatch and no longer creates a second
  authorization failure after stream completion.
- Sandbox tests cover workspace escape, timeout, resource/output limits, network-tool
  denial, and secret-environment removal.

## Database/Flyway acceptance

- Active source locations:
  - `apps/platform-server/src/main/resources/db/platform-server` — V1 through V11.
  - `apps/platform-server/src/main/resources/db/platform-runtime` — V1000.
- Real PostgreSQL 17.9 reported 12/12 successful migrations and schema version 1000.
- No legacy service loads `db/platform-runtime`; platform-server is the only active
  migration owner.
- Verified constraints include Agent API-key name/hash uniqueness, refresh-token digest
  primary key and one-time consumption, Conversation message sequence uniqueness,
  Runtime step/checkpoint/recovery sequence uniqueness, and ToolExecutionLedger
  `(agent_run_id, tool_call_id)` idempotency.
- The strict verifier passed after final live smoke: all counts, bidirectional ID sets,
  checksums, FKs/invariants, and four migration watermarks matched the M8 baseline.

## Build and test evidence

| Gate | Result |
| --- | --- |
| `./mvnw -q test` | PASS |
| `./mvnw -q package` | PASS |
| `./mvnw -q -Plegacy-services package` | PASS without exclusions |
| Java Surefire totals | 443 tests, 0 failures, 0 errors, 0 skipped |
| ai-orchestrator Python (historical, removed M38-PR2) | 13/13 PASS |
| sandbox-worker Python | 9/9 PASS |
| architecture script | PASS |
| Compose config/default services | PASS |
| strict legacy/platform verifier | PASS |
| final platform live smoke | PASS |

The full legacy-profile gate ran with Docker/socket access and executed PostgreSQL 16/17,
pgvector, Redis, and loopback HTTP integration tests rather than reporting environment
skips.

## End-to-end evidence

The final release jar passed:

```text
health
 -> unauthorized rejection
 -> register/login/refresh + replay rejection
 -> second-tenant isolation
 -> Provider/model + encrypted secret
 -> Knowledge document/retrieval/real embedding boundary
 -> Agent + Knowledge binding + runtime configuration
 -> Agent API key create/verify/revoke
 -> Conversation
 -> Chat Runtime/ContextCompiler/real inference boundary
 -> SSE
 -> AgentRun + context checkpoint + Message persistence
```

No paid credential was required: requests reached the real HTTP adapters and returned
explicit `KNOWLEDGE_EMBEDDING_NOT_CONFIGURED` and `INFERENCE_PROVIDER_UNAVAILABLE`
errors, never noop output. Unit/integration suites additionally cover tool-ledger replay,
UNKNOWN ambiguity, Handoff, durable waiting-state resume, and Runtime recovery.

## Python boundary evidence

Python 3.12 unit/HTTP suites passed. Separate live processes completed:

```text
orchestrator /orchestrate -> TOOL_REQUIRED
sandbox-worker /execute -> SUCCEEDED (stdout "hello")
orchestrator /tool-result -> COMPLETED
```

Orchestrator restart replay is deterministic. Java remains the owner of durable run and
tool state; LangGraph remains deferred/imported only by the optional infrastructure
adapter.

## Deployment and legacy state

- Active: platform-server + `spaceagent_platform`.
- Optional: TypeScript multi-agent-orchestrator and OCI sandbox-worker.
- CLI physical path: `cli/`.
- Client work was outside this historical backend acceptance boundary.
- Archived: former backend and five Java service sources are removed from the active tree
  and available only in a private archive not included in this public snapshot. Runtime rollback is not supported.

## Intentional limitations and infrastructure notes

- At the M9 acceptance baseline, Project/Task/Workspace were reference types. Post-M9
  M11-PR1/PR2 add persisted Project/Task foundations; Workspace remains reference-only
  and Task-scoped Runtime binding is still outside this acceptance record.
- Artifact, Automation, Governance, and Observability are package boundaries, not claimed
  product features.
- The default sandbox implementation is a process/resource boundary, not a VM/container
  security boundary for hostile multi-tenant code.
- Real paid-provider content was not required; real adapter reachability/error semantics
  were accepted instead.
- Full Compose image build did not complete because the external 156 MB Temurin JDK layer
  remained bandwidth-limited. Dockerfile metadata/context and the cached JRE stage were
  valid; Maven release packaging, real JVM startup, Docker/Testcontainers, and Compose
  configuration all passed. This is infrastructure-only and does not block M9.

## Completion decision

All critical architecture, security, data, build, Python boundary, deployment, and live
core acceptance gates passed. There is no genuine blocker. M9 is COMPLETE and the
SpaceAgent V2 refactor is COMPLETE.
