# M8 Active and Legacy Runtime Policy

> Superseded by L0 decommission. Legacy Java runtime code is
> `REMOVED_FROM_ACTIVE_TREE / NOT_DISTRIBUTED_IN_PUBLIC_SNAPSHOT`; runtime rollback
> is no longer supported. See `docs/archive/LEGACY-DECOMMISSION.md`.

Date: 2026-08-20

## Runtime authority

`apps/platform-server` is the only active Java business runtime and the only
authoritative owner of Identity, Agent, Conversation/Chat, Knowledge, Memory,
Runtime, and Tooling platform state.

The active request path is:

```text
client -> platform-server -> spaceagent_platform
```

`services/multi-agent-orchestrator` and `workers/sandbox-worker` remain optional isolated
compute/execution adapters. They do not own durable Java business state.

## Rollback-only source and artifacts

The following trees are retained as migration history, compatibility evidence,
and emergency rollback artifacts:

- `backend/`
- `services/gateway-service`
- `services/identity-service`
- `services/agent-service`
- `services/chat-service`
- `services/knowledge-service`

They are deprecated and are not active architecture. The four legacy core
services and the monolith install a write guard when
`legacy.write-mode=rollback-only`. The guard allows read-only inspection and
health probes, but rejects HTTP mutations with `410 LEGACY_WRITE_FROZEN` and
identifies `platform-server` as the authority.

Compose sets rollback-only mode by default. Re-enabling legacy writes requires
an explicit `LEGACY_WRITE_MODE=active` operator decision and must happen only
after active platform writes have been stopped. Running both write paths is not
a supported topology.

An active emergency rollback must target legacy services explicitly so Compose
does not also start the default platform service, for example:

```bash
docker compose stop platform-server
LEGACY_WRITE_MODE=active docker compose --profile microservices up -d \
  identity-service agent-service knowledge-service chat-service gateway-service frontend nginx
```

The equivalent monolith rollback explicitly targets `backend-monolith` and
`nginx-monolith`. Operators must first confirm the data rollback/watermark plan;
the M8 backfill is not a reverse-sync mechanism.

## Deployment and build boundaries

- Default `docker compose up`: PostgreSQL/Redis initialization plus
  `platform-server` on port 9000.
- `--profile microservices`: deprecated five-service rollback topology.
- `--profile monolith`: deprecated monolith rollback topology.
- Default root Maven reactor: `shared/shared-kernel` and
  `apps/platform-server`.
- `-Plegacy-services`: explicitly includes the five legacy Java services for
  rollback builds and compatibility tests.

The default deployment has no gateway. Clients use platform-server directly.
The legacy gateway is available only inside the rollback profile and therefore
does not route active Identity, Agent, Chat, or Knowledge traffic.

## Flyway ownership

`apps/platform-server` is the sole active migration owner for
`spaceagent_platform` and loads only:

- `classpath:db/platform-server`
- `classpath:db/platform-runtime`

Legacy service migrations remain service-local historical/rollback material.
`chat-service` no longer loads `db/platform-runtime`, and no legacy migration is
permitted to run against `spaceagent_platform`.

## Source-retention rule

Runtime retirement is complete without blanket source deletion. A retained
legacy source may be deleted later only when it has no rollback, migration,
historical, or compatibility-test value and no caller. Source deletion is not a
condition for M8 completion.
