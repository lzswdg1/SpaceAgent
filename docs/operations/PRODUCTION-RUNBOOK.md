# SpaceAgent Trusted Beta Production Runbook

## Release boundary

M30 supports an API/CLI-first `TRUSTED_BETA` deployment. M34 adds an optional disposable
Docker/OCI sandbox for Coding commands, but public-untrusted enablement still requires real
Linux Engine 26+ plus gVisor acceptance. `PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED=true`
therefore remains rejected.

Remote GitHub branches are not updated by platform-server. M29 produces a reviewed local
integration ref and manual Patch delivery evidence only.

## Required topology

- Java 21 `platform-server`, running as a non-root container;
- PostgreSQL 17 with pgvector, durable volume and schema V1098;
- the private Sandbox profile in HTTP/OCI mode whenever Project intake or autonomous coding is enabled;
- durable `/data/workspaces` volume owned by the application user;
- external TLS reverse proxy/load balancer forwarding standard headers;
- real HTTP inference and embedding adapters;
- TypeScript Multi-Agent remains optional and explicitly configured.
- Docker Engine 26+ and the private sandbox-worker are required only when
  `SANDBOX_MODE=http`.

Redis and Kafka are not active dependencies. The observability profile remains optional for its
telemetry role and is never recovery truth.

Artifact bytes default to the persistent local Workspace volume with `ARTIFACT_OBJECT_MODE=local`. For an
operator-controlled S3-compatible service set `ARTIFACT_OBJECT_MODE=s3` plus endpoint/bucket/access/secret values
through the deployment secret manager. Never commit or expose those values. Startup does not create or mutate a
remote bucket; provision it and its encryption/access policy separately. Object deletion is bytes-first and any
storage ambiguity leaves PostgreSQL state BLOCKED for operator reconciliation rather than deleting metadata.
The bundled Observability profile binds Prometheus, Alertmanager, Grafana, Loki and Tempo to
loopback only.
Keep it private or place an authenticated TLS proxy in front; set a strong
`GRAFANA_ADMIN_PASSWORD` before enabling the profile.

## Configure and preflight

```bash
cp .env.release.example .env.release
# Replace every placeholder with a generated or provider-issued value.
RELEASE_ENV_FILE=.env.release ./scripts/release-preflight.sh
```

The preflight never prints Secret values. It validates lengths/placeholders, trusted-only
audience, strict release Compose, package/architecture gates, Git state and optional live
readiness.

Deploy behind TLS:

```bash
docker compose --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d --build
```

Enable the private observability profile and Java OTLP export explicitly:

```bash
PLATFORM_OBSERVABILITY_OTLP_ENABLED=true \
docker compose --env-file .env.release --profile observability \
  -f docker-compose.yml -f docker-compose.release.yml up -d --build \
  platform-server prometheus alertmanager grafana loki tempo alloy
```

Prometheus scrapes Micrometer at `/actuator/prometheus`; Tempo receives sampled OTLP/HTTP traces;
Loki receives container/application logs; Grafana provisions JVM and Agent operations dashboards;
Alertmanager groups and silences both infrastructure and Agent SLO alerts. The checked-in
`local-control-plane` receiver has no outbound integration. Before release, mount a separately
managed Alertmanager configuration for the designated email/PagerDuty/webhook receiver and verify a
synthetic alert reaches the on-call operator. Receiver credentials must stay outside Git.

Do not expose PostgreSQL or Redis publicly. The release overlay binds platform-server to
`PLATFORM_BIND_ADDRESS` (the example uses loopback) so the TLS proxy is the public edge.
The base Compose file is loopback-first after M37. Health and Prometheus are the only
anonymous Actuator routes; Swagger and other exposed management endpoints require authentication.
If the application listen address is expanded beyond loopback/private networks, protect
`/actuator/prometheus` with a dedicated authenticated proxy or separate management network first.

To isolate Coding commands, set `SANDBOX_MODE=http`, optionally set a distinct
`SANDBOX_INTERNAL_TOKEN`, and start with `--profile sandbox`. The worker owns the Docker
socket and must have no published port. Child containers do not receive that socket. Set
`SANDBOX_OCI_RUNTIME=runsc` only after gVisor is installed in the daemon. Preflight requires
Docker API 1.45+ and a non-root execution UID/GID. Missing dependencies should be baked into
the operator-owned worker/toolchain image; the execution container has no network.

The worker control process is trusted infrastructure: compromise of a process with the writable
Docker Socket has impact close to host root even when its child containers are non-root,
network-disabled and capability-free. The profile is default-off and is not approved for arbitrary
public code. Prefer a dedicated execution node, rootless Docker or a restricted Socket proxy with an
explicit API allowlist. Do not expose the worker HTTP port through a host or public proxy.

Alloy's `:ro` Docker Socket mount does not create a read-only Docker API. It only prevents replacing
the socket path through that mount. A compromised collector can still send daemon operations allowed
by the Socket, so the observability stack must remain private and trusted.

The optional TypeScript orchestrator requires a distinct 32+ character internal Bearer.
Set `MULTI_AGENT_ORCHESTRATOR_INTERNAL_TOKEN` in that process and the corresponding
`PLATFORM_MULTI_AGENT_ORCHESTRATOR_INTERNAL_TOKEN` value in Java. Never expose its POST
route through the public proxy.
Automatic Chat planning stays off by default. To enable the completed M54 planning/execution loop,
set `CHAT_AUTOMATIC_PLANNING_ENABLED=true`, `MULTI_AGENT_ORCHESTRATOR_MODE=http` and an explicit
`MULTI_AGENT_ORCHESTRATOR_BASE_URL` reachable from platform-server. Preflight and startup reject an
enabled flag without the HTTP orchestrator; Provider and Tool effects still remain Java-ledgered.

The optional `platform-admin-server` is a separate private management ingress and database. Set a
distinct `ADMIN_DB_PASSWORD`, `ADMIN_JWT_SECRET`, `SYSTEM_ADMIN_JWT_SECRET` and
`IDENTITY_ACTIVITY_HASH_KEY`; set `ADMIN_COOKIE_SECURE=true`. Release preflight rejects empty,
short, reused or development-marker values and rejects an all-interface/public Admin host bind.
Private calls to
`/internal/system-admin/v1/**` require an HTTPS connection with a client certificate plus an
exact-scope service JWT lasting at most 60 seconds; the generic internal token is rejected. Mount
the client key/trust stores through standard JVM `javax.net.ssl.keyStore`/`trustStore` settings and
keep `SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL=false`. Do not route `/admin/**` or
`/internal/system-admin/**` through the ordinary user-facing proxy.

The Admin plane has exactly one non-tenant `SystemAdministrator`. Under ADR-087, configure persistent
`ADMIN_LOGIN` and `ADMIN_PASSWORD` in the deployment secret source. Every startup synchronizes that
singleton; changed credentials revoke old sessions. Login uses password only; TOTP, recovery codes,
browser password changes and legacy break-glass are retired. This has lower authentication strength
than MFA, so retain the private ingress controls above and a strong unique password. Recovery means
changing the environment and restarting. Back up the Admin database before V5 and deploy Admin API/Web
together; old MFA binaries cannot safely use new password-only sessions. Never commit the real `.env`.
The Admin JAR defaults to `127.0.0.1`; Compose explicitly binds it to `0.0.0.0` only inside the
container while publishing the host port to the configured loopback/private address.

To enable the official GitHub Remote MCP Marketplace profile, register a dedicated GitHub
App or OAuth App and set `GITHUB_MCP_CLIENT_ID`, `GITHUB_MCP_CLIENT_SECRET` and an exact
comma-separated `GITHUB_MCP_ALLOWED_REDIRECT_URIS`. The backend discovers OAuth endpoints
from the MCP challenge and rejects direct PAT configuration. Run
`.agents/skills/spaceagent-real-e2e/scripts/inspect_github_prerequisites.sh` before browser
acceptance. Do not store or use a GitHub account password in release configuration.
Set `MCP_ALLOWED_HOSTS=api.githubcopilot.com,github.com` and add only explicitly reviewed
custom MCP hosts. An unlisted custom host may be stored as configuration evidence but cannot
perform discovery or Tool calls.
For generic OAuth, configure `MCP_OAUTH_REGISTRATION_ID`, `MCP_OAUTH_AUTHORIZATION_SERVER`,
`MCP_OAUTH_CLIENT_ID`, `MCP_OAUTH_CLIENT_SECRET`, client authentication method, minimal scopes and
an exact redirect allowlist. Add both the resource and authorization/token hosts to
`MCP_ALLOWED_HOSTS`. Empty generic OAuth settings keep the capability disabled. A callback stores
only an encrypted grant and leaves the Connection `PENDING_VALIDATION`; qualify it before use.
New or reconfigured Connections remain `PENDING_VALIDATION` until an authorized manual
qualification completes MCP initialize and bounded Tool discovery. `ERROR` indicates initial
qualification failure; `DEGRADED` indicates an active Connection failed requalification. Inspect
the secret-free qualification status and health-observation APIs before retrying or disabling it.

## Health and lifecycle

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Metrics: `/actuator/prometheus`
- Prometheus UI: loopback `:9090`
- Alertmanager UI/API: loopback `:9093`
- Grafana: loopback `:3000`
- Tempo API: loopback `:3200`

Agent metrics are low-cardinality global projections and intentionally contain no tenant/resource
identifiers. They cover rolling Run/Model/Tool outcomes, UNKNOWN counts, P95 latency, token/cost
completeness, recovery, Handoff/Review and Acceptance evidence. Multiple replicas expose the same
database-derived values; queries must use `max` across instances. Metrics or trace loss never
authorizes retry and never changes Runtime state.

Readiness requires PostgreSQL schema V1098, no failed Flyway migration, a writable managed
Workspace and the trusted-only release audience. Provider availability is intentionally not
liveness: a Provider outage must not restart the Java control plane.

M52-PR2 uses the same `PLATFORM_OBSERVABILITY_OTLP_ENABLED` and
`PLATFORM_OBSERVABILITY_OTLP_ENDPOINT` for Java and the optional Sandbox profile. Standalone
TypeScript deployments also accept `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`. Keep export disabled until
the private collector is reachable. The emitted GenAI subset is pinned by ADR-052; never enable
content capture or add business identifiers to spans. PostgreSQL ModelCallLedger is the TTFC source
of truth and remains valid when Tempo is unavailable.

Containers receive SIGTERM and Spring performs graceful shutdown with a 30-second phase
budget. Remove a replica from load balancing when readiness becomes DOWN before stopping it.

## Backup and restore

Create an atomic PostgreSQL custom-format backup and SHA-256/schema manifest:

```bash
RELEASE_ENV_FILE=.env.release ./scripts/backup-platform.sh
```

Every backup must pass a disposable restore before it is accepted:

```bash
RELEASE_ENV_FILE=.env.release \
  ./scripts/verify-platform-backup.sh .run/backups/spaceagent-platform-<timestamp>.dump
```

Restore requires an exact target confirmation. In-place restore additionally requires the
platform to be stopped and two explicit acknowledgements:

```bash
RESTORE_TARGET_DATABASE=spaceagent_platform_restore \
RESTORE_CONFIRM_DATABASE=spaceagent_platform_restore \
RELEASE_ENV_FILE=.env.release \
  ./scripts/restore-platform.sh <backup.dump>
```

Never use an unverified backup. Workspace Git volume backup/retention is separate from
PostgreSQL: database restore recovers authoritative metadata/ledgers but cannot recreate a
deleted managed worktree. Retain repository mirrors/workspaces consistently with the
database backup window or reprovision from SourceRepository plus stored Patch evidence.

## Golden path and restart

The release regression uses the existing deterministic OpenAI-compatible fixture and the
platform API regression. Run `prepare`, restart the Java service, then verify durable
Identity/Provider/Agent/Knowledge/Conversation/Memory/Run/Checkpoint/Tool evidence:

```bash
RELEASE_GOLDEN_PATH_PHASE=full ./scripts/release-golden-path.sh
```

For an externally managed JVM, run `prepare` and `verify-restart` separately around the
deployment restart. The test database should be disposable/staging; never point regression
cleanup at production.

## Incident handling

User deletion is a durable operation. Confirm `PLATFORM_USER_DELETION_ENABLED=true` only on an
accepted release, suspend the User, inspect deletion preflight, then create the deletion Job. Monitor
`/admin/v1/users/{userId}/deletion-jobs/current`; never retry the request with a different key after
an ambiguous dispatch. `BLOCKED` means an explicit Organization transfer, MCP/Project ownership or
UNKNOWN-effect reconciliation is required. Do not edit Job/Step tables manually.

- User cleanup `RETRY`: wait for `nextAttemptAt`; lease/Organization drain is automatic.
- User cleanup `BLOCKED`: resolve the reported owner/UNKNOWN condition; do not bypass the fence.
- User `DELETED`: active/private data is erased, while the pseudonymized stable-ID tombstone remains.

- Model/Tool/Automation/SourceMerge `UNKNOWN`: stop automatic progression, inspect the
  owner ledger and use only its explicit reconcile path.
- SourceMerge drift: leave CONFLICT intact; create a new Workspace/Proposal after reviewing
  the new Base. Never force-update.
- Provider outage: disable the unhealthy Provider/member or rely on known-safe fallback;
  UNKNOWN does not fall back.
- Cleanup BLOCKED: inspect CleanupJob/Step safe error evidence and repair the owning module
  or external storage adapter before retrying.
- Secret exposure suspicion: rotate JWT/internal and affected encryption/provider keys;
  retain previous encryption keys only for the controlled re-encryption window.

## Rollback

Application rollback is a previous immutable image using the same compatible PostgreSQL
schema. Flyway migrations are forward-only; never delete or rewrite an applied migration through
the release's expected V1098 schema.
For data rollback, stop writers, take a final backup, restore a previously verified dump into
an explicitly confirmed database, run readiness and the restart verification, then switch
traffic.
