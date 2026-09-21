# Legacy Runtime Decommission

- Status: COMPLETE
- Private archive: not included in the public source snapshot
- Active backend: `apps/platform-server`
- Authoritative database: `spaceagent_platform`

## Why the code was removed

The modular Java platform completed the V2 cutover and became the only active HTTP/SSE runtime and
authoritative owner of core business and execution state. The former monolith and five retired Java
service sources no longer serve an active build, deployment, route or Flyway ownership role.

Keeping non-runnable rollback profiles in the active tree increased maintenance and security review
surface and made the supported topology ambiguous. The public snapshot therefore contains only the
active implementation. Private repository commits and archive tags are deliberately omitted and
cannot be used as public dependencies or rollback instructions.

## Removed scope

- the former monolithic backend;
- retired gateway, identity, agent, chat and knowledge Java services;
- their Maven modules, images, Compose profiles, volumes, routes and local scripts;
- write-mode, active-mode and runtime rollback configuration;
- the retired Python AI orchestration runtime.

The TypeScript `services/multi-agent-orchestrator`, OCI `workers/sandbox-worker`, current contracts,
CLI, React clients, shared kernel and active platform migrations remain.

## Migration evidence retained

- `apps/platform-server/src/main/resources/db/platform-server/V11__m8_migration_framework.sql`;
- `scripts/m8_backfill.sql` and `scripts/m8_migration_checks.sql`;
- `scripts/migrate-m8-data-cutover.sh` and `scripts/verify-m8-data-cutover.sh`;
- `docs/architecture/M8-DATA-MIGRATION-MAP.md`;
- `docs/architecture/M8-FINAL-CUTOVER-EVIDENCE.md`;
- PostgreSQL migration run, watermark and ID-map tables.

These artifacts preserve current migration invariants without distributing the retired source.

## Rollback policy

Legacy runtime rollback is not supported. Recovery means restoring or redeploying a compatible
immutable `platform-server` image and its authoritative `spaceagent_platform` data. Do not recreate
or copy retired runtime code into the active tree. Any future compatibility runtime requires a new
architecture decision and independent security review.
