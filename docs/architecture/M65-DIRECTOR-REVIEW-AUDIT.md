# M65 Director Review Production-Wiring Audit

> Updated: 2026-09-09
> Scope: backend production paths only
> Result: M65-PR4 final remediation reopened; prior deterministic completion is not sufficient

This audit uses current production source as authority. Existing domain, repository or mock tests are recorded as
partial evidence only. A finding is not closed until the promised behavior is exercised through its real HTTP,
worker, continuation or coordinator entry and, where persistence is involved, PostgreSQL concurrency/restart proof.

## 1. Ordinary Agent writes bypass version review

- Production entry: `PlatformAgentHttpController.createAgent`, `updateAgent`, `patchAgent` and
  `replaceKnowledgeBindings` call `AgentApplicationApi.create/update` directly.
- Break: `AgentApplicationService.create` inserts a first version and immediately switches `currentAgentVersionId`;
  `update` inserts changed configuration and immediately switches the current version. Neither path creates an
  IN_REVIEW draft nor requires the M63 review/approval evidence used by `AgentVersionWorkflowApplicationService`.
- Partial evidence: explicit draft/review/publish endpoints do require approved exact-hash review.
- Missing proof: real ordinary HTTP create/update must show no unreviewed configuration becomes runtime-current.
- Remediation: `M65-PR2-U02`.
- Closure evidence: ordinary create/update now leaves configuration in DRAFT, exact review/publish selects current,
  and public GET/list resolves current rather than a later draft. Real Spring HTTP and full regression pass.

## 2. Normal mcp_call does not enforce exact AgentVersion MCP binding

- Production entry: `RuntimeToolExecutionApplicationService.execute` resolves the pinned AgentVersion configuration,
  but the mcp_call path only checks that `mcp_call` is enabled and then trusts request `connectionId`/`remoteTool`.
- Break: `ExternalRuntimeToolApplicationApi.ExecuteCommand` carries no AgentVersion/binding identity, and descriptor/
  invocation are reached without matching connection, server version, capability snapshot and allowed remote tool
  against `AgentVersionRuntimeConfigurationView.mcpBindings`.
- Partial evidence: publication-time binding validation and UNKNOWN reconciliation tests exist.
- Missing proof: normal Runtime execution must reject unbound/substituted Connection/tool before ledger side effects.
- Remediation: `M65-PR2-U03`.
- Closure evidence: normal Runtime `mcp_call` rejects Connection/tool substitution before Tool Ledger or remote work
  unless it matches the pinned AgentVersion's exact MCP binding. Positive/negative Runtime production tests pass.

## 3. Scheduled AgentVersion publication is not fully lease-safe or crash-recoverable

- Production entry: `AgentVersionActivationWorker.poll` calls `AgentVersionReviewApplicationService.executeNext`.
- Break: PostgreSQL `claimNext` only selects SCHEDULED rows, so an expired CLAIMED row is never reclaimed. There is no
  heartbeat/renew path. Agent revision/current-version and review/hash policy are checked before a separate lifecycle
  publication transaction; final schedule CAS does not make those preconditions atomic with the Agent switch.
- Partial evidence: SKIP LOCKED single claim and claim-token/fencing completion CAS are tested.
- Missing proof: expired lease reclaim, stale worker rejection, bounded attempts, crash between claim/publish/finish,
  and one durable publication through the real worker.
- Remediation: lease lifecycle in `M65-PR2-U04`; atomic publication/recovery in `M65-PR2-U05`.
- Closure evidence: PostgreSQL DB-clock reclaim, heartbeat, bounded attempts, stale fencing and one transactional
  precondition/publication/finish path pass multi-worker, crash and restart tests through the production worker.

## 4. Local chunk upload buffers the entire request before enforcing bounds

- Production entry: `PlatformLocalProjectMaterializationHttpController.chunk` binds `@RequestBody byte[]` and compares
  its length only after Spring has materialized the complete body.
- Break: the staging adapter supports an InputStream, but the HTTP boundary defeats streaming and late rejection can
  consume unbounded heap when Content-Length is absent or false.
- Partial evidence: filesystem staging validates expected length and SHA-256 after receiving a stream.
- Missing proof: real HTTP Content-Length precheck plus bounded stream enforcement for absent/lying headers.
- Remediation: `M65-PR2-U06`.
- Closure evidence: servlet HTTP reads a bounded stream directly, rejects declared oversize before reading and detects
  absent/lying lengths at the cap. Real MockMvc transport and negative tests pass without `@RequestBody byte[]`.

## 5. M58 parallel DAG, concurrency and merge barrier are disconnected from production scheduling

- Production entry: Project Coding uses `ProjectCodingCoordinator` and `ProjectCodingJobApplicationService`.
- Break: `ProjectPlanReadyWave`, `ProjectPlanWaveConcurrencyRepository` and `ProjectPlanMergeBarrierRepository` have no
  production Application/Coordinator caller. Existing jobs therefore do not prove ready-wave selection, shared
  PostgreSQL slot limits or stable reviewed merge application during normal dispatch.
- Partial evidence: pure domain and repository tests cover these primitives in isolation.
- Missing proof: multi-worker scheduler entry, independent Workspace/Job dispatch, restart-safe slot release, stable
  merge order, base-drift reconciliation and downstream waiting through the actual coordinator.
- Remediation: `M65-PR2-U08`, `M65-PR2-U09`, `M65-PR2-U10`.
- Closure evidence: production Coding selects deterministic ready waves, acquires PostgreSQL slots, creates isolated
  Workspace/Jobs and applies stable-order barriers; drift creates Project reconciliation and waits downstream.

## 6. M59 graph/v2, long-running commands and Supervisor rollout are disconnected

- Production entry: current orchestration uses the earlier `MultiAgentOrchestrationApplicationService` contract.
- Break: `GraphV2RuntimeApplicationService` only accepts a result into a session/command record and has no production
  caller; TypeScript `transitionGraphV2` is not exposed by the server route. PENDING `RuntimeGraphCommand` has no
  claim/lease/continuation executor. `SupervisorPolicy` and `SupervisorPolicyResolution` are only domain/test inputs
  and are not pinned or selected when a real Run invokes orchestration.
- Partial evidence: Java/TypeScript graph/v2 schema tests and Supervisor policy unit tests exist.
- Missing proof: Java-to-TS production call, durable long command resume/restart/UNKNOWN and rollout/kill-switch/shadow
  behavior through a real Run.
- Remediation: `M65-PR2-U11`, `M65-PR2-U12`, `M65-PR2-U13`.
- Closure evidence: authenticated graph/v2 Java-to-TypeScript transport, Java command continuation/fencing and immutable
  per-Run Supervisor policy pins now execute through production entry points; restart/UNKNOWN/kill-switch tests pass.

## 7. Trigger management, event admission and automatic dispatch do not form a closed loop

- Production entry: webhook/repository/internal services validate active subscriptions and create READY occurrences;
  event execution can dispatch and continue a known occurrence.
- Break: `AutomationTriggerApplicationApi` has no production implementation or HTTP adapter, so normal users cannot
  create/version/activate/pause/archive the records admission requires. No production caller constructs
  `AutomationEventExecutionApplicationApi.DispatchCommand` for a newly READY occurrence.
- Partial evidence: admission and execution services are tested separately with manually seeded repositories/mocks.
- Missing proof: tenant management HTTP through active subscription, signed/internal event admission, automatic claim/
  dispatch, Runtime continuation, duplicate event replay, restart and UNKNOWN handling.
- Remediation: `M65-PR2-U14`, `M65-PR2-U15`.
- Closure evidence: tenant Trigger management, active subscription admission and PostgreSQL READY occurrence worker now
  form one production loop with deduplication, Runtime continuation, reclaim and UNKNOWN fail-closed proof.

## 8. Follow-up materialization requests do not revalidate Bridge revocation/session expiry

- Production entry: only materialization `start` requires the Bridge token and calls Bridge heartbeat. Manifest, chunk
  and finalize endpoints omit the token and Bridge API entirely.
- Break: service follow-ups load the stored session but do not verify current Bridge state/device/root binding. Chunk
  and finalize do not consistently reject `time.now() >= expiresAt`; replay branches can also bypass active checks.
- Partial evidence: session domain rejects expiry only when beginning upload, and start authentication is unit tested.
- Missing proof: revoke/expire races at manifest/chunk/finalize through real HTTP and persisted restart state.
- Remediation: `M65-PR2-U07`.
- Closure evidence: manifest/chunk/finalize reauthenticate the transient Bridge token and exact device/root binding and
  reject revoked Bridge, expired Session and substitution before byte/snapshot effects, including restart tests.

## Batch and release boundary

- B01: U01-U05, Agent/MCP/scheduled activation production paths.
- B02: U06-U10, materialization and M58 Project scheduling/barrier paths.
- B03: U11-U15, graph/v2/Supervisor/Automation paths.
- M65-PR3: combined deterministic production entry, concurrency, restart, upgrade/backup/restore, cleanup and full gates.
- `.agent/EXTERNAL-ACCEPTANCE.md`: Linux/runsc, private ingress, live telemetry/alerts and credential-bound integrations.
  These remain release-blocking and cannot be inferred from deterministic fixtures.
- M65-PR3 final evidence: clean Maven `687/687`, TypeScript `15/15`, Python `23/23`, package, six Compose configs,
  architecture/boundary scans and disposable V1073/85 backup/restore all passed in the recorded local validation.

## M65-PR4 final findings

- U01: applied V1072/V1073 history was edited for partial-schema tests; restore original bytes and validate restart
  against original checksums, using only V1074 for genuine forward schema changes.
- U02: make Coding Job terminal evidence and Barrier Entry atomic, then drain external Git/SourceMerge work through a
  restartable CAS/lease/fenced worker outside long database transactions.
- U03: tie Wave concurrency claims to authoritative Coding Job lease renewal and token+fence release so long work stays
  counted and stale workers cannot release or complete another claim.
- U04: canonical graph command input hashes, exact cursor continuity and a real Java command executor must close all
  MODEL/Tool/Delegation/Handoff paths through existing public APIs, Ledgers and UNKNOWN semantics.
- U05: persist Automation dispatch phases and stable IDs across the pre-Run crash window, add owner approval resume and
  prevent UNKNOWN occurrences from redispatching effects.
- U06: Shadow must make a real Provider candidate call through ModelPool/Budget/ModelCallLedger, compare and record
  metrics/trace, but never execute candidate business commands or alter the deterministic result.
- U07: only clean checksum/crash/restart/production-entry/negative evidence plus final gates may restore the
  `DETERMINISTIC_BACKEND_COMPLETE` status. External acceptance remains separate and unexecuted.

## M65-PR4-B01 closure evidence

- U01-U05 passed Java `93/93` and TypeScript `15/15` with Docker/PostgreSQL and zero skips.
- V1072/V1073 are original-byte immutable and restart-validated; V1074-V1077 are forward-only. Project Barrier/Wave,
  graph command execution and Automation dispatch/approval recovery now have production-entry, crash, lease/fence,
  stale-worker, cursor/hash and UNKNOWN negative proof.

## M65-PR4-B02 and final closure evidence

- U06-U07 passed focused `14/14`. SHADOW invokes the existing Java-owned Inference path with ModelPool resolution,
  Budget reserve/settle and durable ModelCallLedger, compares canonical candidate evidence and never dispatches the
  candidate command. Provider failure records only `UNKNOWN_CANDIDATE` and preserves the deterministic result.
- Clean final Maven passed Shared `24/24`, Platform `666/666` and Admin `9/9` = `699/699`, with zero failures, errors
  or skips. Package, architecture and milestone boundary/generated/credential checks also passed.
- All eight original findings plus the seven M65-PR4 final remediation Units now have deterministic local evidence.
  `DETERMINISTIC_BACKEND_COMPLETE` is restored. Linux/runsc, production mTLS, live OTLP/alerts and credential-bound
  Provider/MCP/S3 acceptance remain `EXTERNAL_ACCEPTANCE_PENDING` and still gate public-untrusted release.
