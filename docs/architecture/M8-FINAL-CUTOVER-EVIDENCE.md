# M8 Final Cutover Evidence

> Historical cutover evidence. Legacy source paths now resolve only in Git tag
> The retired source is not distributed in this public snapshot; active Migration Evidence tables
> and verification scripts remain.

Date: 2026-08-20

Status: **M8 COMPLETE. M9 subsequently completed; see `V2-FINAL-ACCEPTANCE.md`.**

This document records the real local platform database bootstrap, Flyway migration,
ID-preserving backfill, idempotent rerun, strict legacy/platform validation, live JVM
gate, core HTTP/SSE smoke, routing cutover, legacy write freeze, and runtime retirement.

## Platform database bootstrap

- Created database: `spaceagent_platform`.
- PostgreSQL: 17.9, Compose service `postgres`, host port 5436.
- Platform tables present: 29.
- Successful Flyway rows: 12.
- Applied versions: V1-V11 plus V1000 runtime.
- `flyway_schema_history` records installation timestamp, Flyway checksum,
  execution time, and success for every migration.

Key migration history:

| Version | Description | Flyway checksum | Result |
| --- | --- | ---: | --- |
| 1 | identity | 1480467819 | success |
| 2 | agent | -1401442090 | success |
| 3 | inference | -1356773157 | success |
| 4 | knowledge | -197368103 | success |
| 5 | memory | -408856748 | success |
| 6 | conversation | -171775113 | success |
| 7 | identity credentials | 2130787375 | success |
| 8 | identity sessions and memberships | -275642595 | success |
| 9 | agent keys and configuration | -2036527101 | success |
| 10 | knowledge processing and retrieval | -1894030829 | success |
| 11 | M8 migration framework | -2064782724 | success |
| 1000 | runtime/tooling/snapshots | 1544962964 | success |

## Migration framework

Flyway V11 owns:

- `platform_migration_runs`
- `platform_migration_watermarks`
- `platform_migration_id_map`

The reusable job is `scripts/migrate-m8-data-cutover.sh`, backed by
`scripts/m8_backfill.sql`. It:

1. creates the platform database idempotently;
2. applies platform Flyway migrations;
3. stages each legacy domain independently through `dblink`;
4. uses `INSERT ... ON CONFLICT DO UPDATE` for idempotent replay;
5. commits each domain independently;
6. preserves legacy IDs and records every mapping;
7. records last migrated ID, counts, checksums, timestamps, and status;
8. runs strict validation and marks the migration run VERIFIED only after success.

Job version: `m8-backfill-v1`

Job checksum:
`603ca5002b5087bc96a230e2f490bfa447db294f79832cb3afe70d26406de66a`

## Verified migration runs

Two isolated migration runs were recorded: the initial backfill/validation and an idempotent,
resumable rerun. Private local run identifiers and `.run` log paths are not distributed in this
public snapshot.

The second run retained identical row counts and checksums and produced no duplicate
IDs, proving safe re-entry.

## Domain watermarks

| Domain | Source count | Target count | Source checksum | Target checksum | Status |
| --- | ---: | ---: | --- | --- | --- |
| identity | 24 | 24 | matched; historical digest omitted | same | MIGRATED |
| agent | 2 | 2 | matched; historical digest omitted | same | MIGRATED |
| chat | 27 | 27 | matched; historical digest omitted | same | MIGRATED |
| knowledge | 0 | 0 | matched; historical digest omitted | same | MIGRATED |

Identity count includes users, tenants, and memberships. Agent count includes Agent
configurations/API keys/Knowledge bindings. Chat count includes conversations/messages/
snapshots. Knowledge count includes documents/chunks.

## Strict legacy/platform validation

The enhanced verifier passed after both migration runs, after adding watermark
consistency checks, and again after each final live smoke cleanup.

| Domain/entity | Legacy | Platform | ID set | Canonical checksum | FK/invariants |
| --- | ---: | ---: | --- | --- | --- |
| identity users | 7 | 7 | equal | matched; historical digest omitted | pass |
| identity tenants | 8 | 8 | equal | matched; historical digest omitted | pass |
| identity memberships | 9 | 9 | equal | matched; historical digest omitted | pass |
| Agent configurations | 2 | 2 | equal | matched; historical digest omitted | pass |
| Agent API keys | 0 | 0 | equal | empty checksum | pass |
| chat conversations | 8 | 8 | equal | matched; historical digest omitted | pass |
| chat messages | 19 | 19 | equal | matched; historical digest omitted | pass |
| Knowledge documents | 0 | 0 | equal | empty checksum | pass |
| Knowledge chunks | 0 | 0 | equal | empty checksum | pass |

Platform invariant checks also passed for:

- tenant/user ownership;
- Agent/provider/model/Knowledge binding ownership;
- API-key hashes and references;
- conversation/message sequence continuity;
- Knowledge embedding metadata/FKs;
- runtime/tool-ledger FKs;
- required four-domain watermarks and source/target checksum equality.

## ID map evidence

- Identity: 7 users, 8 tenants, 9 memberships mapped with equal IDs/composite keys.
- Agent: 2 definitions and 2 configurations mapped with equal IDs.
- Inference: 2 providers and 2 provider models mapped with equal IDs.
- Chat: 8 conversations and 19 messages mapped with equal IDs.
- Agent API keys, chat snapshots, Knowledge documents/chunks were empty in the source.

No plaintext Agent API key was created or reconstructed. Model-provider ciphertext was
copied as opaque ciphertext. Knowledge embeddings would be copied as their existing
vector text/reference and never regenerated; the current source Knowledge dataset is
empty.

## Docker Compose evidence

- Added `apps/platform-server/Dockerfile`.
- Made `platform-server` the default active Compose Java service.
- Added `spaceagent_platform` to idempotent database initialization.
- Platform service points only to `spaceagent_platform`.
- Legacy `microservices` and `monolith` profiles remain rollback-only and write-frozen
  by default.
- `docker compose config --quiet` passes.

The first image build was cancelled after the external Temurin/Ubuntu mirror remained
stalled for several minutes. This is external image-download evidence, not a database or
M8 failure. Per the completion rule, the final `-exec.jar` was instead run with the real
production datasource/Flyway/inference/Knowledge configuration.

## Platform live gate and core smoke

- `platform-server-0.0.1-SNAPSHOT-exec.jar` connected to PostgreSQL 17.9 at
  `spaceagent_platform`.
- Flyway validated 12/12 migrations and reported schema version 1000 current.
- Spring application context and Tomcat port 9000 started; `/actuator/health` returned
  `UP`.
- `scripts/smoke-platform-live.sh` passed Identity register/login/refresh/current-user,
  provider/Agent creation, Agent runtime configuration and Knowledge binding, Knowledge
  document/retrieval/processing boundary, Conversation create, sync Chat, and SSE Chat.
- Runtime evidence showed two AgentRuns, two context-compiled checkpoints, and two
  persisted Messages for sync/SSE requests.
- With no paid credentials, Knowledge returned the explicit real-adapter configuration
  error and Chat returned the explicit real-provider unavailable error. No noop path ran.
- Existing platform suites retained checkpoint/recovery scenarios, including durable
  waiting-state resume and Chat recovery compatibility.

## Cutover and retirement evidence

- Default Compose services are `postgres`, `database-init`, `redis`, and
  `platform-server`; there is no active gateway/core legacy route.
- The four legacy core services and legacy backend have an explicit conditional write
  guard. Compose defaults it to rollback-only and rejects mutations with HTTP 410.
- Default Maven modules are shared-kernel and platform-server. The five legacy Java
  modules moved to the explicit `legacy-services` profile; their profile build passes.
- `chat-service` no longer loads `db/platform-runtime`; platform-server alone owns active
  platform/runtime Flyway migrations.
- Shared-kernel has no business service client/DTO, business event payload, or business
  persistence contract; executable shell/JUnit gates enforce this boundary.
- Legacy source is retained as documented DEPRECATED/ROLLBACK_ONLY evidence and is not
  part of active routing, default deployment, default reactor, or authoritative state.

## Status decision

All M8 Data Migration Execution completion conditions are met:

- platform database exists;
- Flyway completed successfully;
- ID-preserving backfill completed;
- watermarks and ID maps exist;
- strict count/ID/checksum/FK validation passed;
- an idempotent rerun also passed.

Final gates also passed:

- platform/default Maven tests: 115 tests, 0 failures, 0 errors, 3 sandbox Docker-socket
  skips;
- architecture validation;
- Compose configuration and default-service topology;
- legacy-profile package and write-freeze unit tests;
- final strict legacy/platform count, ID, checksum, FK, and watermark verification.

Therefore M8c, M8d, M8e, M8f, M8g, and M8h are COMPLETE. Overall M8 is COMPLETE.
M9 remains NOT_STARTED.
