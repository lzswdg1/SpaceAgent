# SpaceAgent V2 Development Runbook

This runbook starts the active platform directly. Legacy services are not required.

## 1. Configure local secrets

Copy `.env.example` to `.env` and replace the database password, JWT secret, internal
token, and provider-encryption key before the first database start. Active PostgreSQL
mode rejects known development placeholders. Use values of at least 32 characters for
the three platform secrets and at least 16 characters for the database password.

## 2. Start PostgreSQL

```bash
docker compose up -d postgres database-init
docker compose ps
```

`database-init` creates `spaceagent_platform` idempotently.

### 2.1 Fast daily feedback loop

Do not rebuild the application container after every source edit. Keep PostgreSQL running and choose
the first sufficient path:

```bash
# Start/reuse only persistent dependencies.
make dev-deps

# Verify source with the affected test class or feature set.
./mvnw -pl apps/platform-server -am \
  -Dtest=<AffectedTest> -Dsurefire.failIfNoSpecifiedTests=false test
```

If a current HTTP process is required, run `platform-server` from the host using section 3. If only
environment values changed, use `make recreate-platform`; it recreates the service from the existing
image. If container packaging itself must be proved after a source, resource, dependency or
Dockerfile change, use `make rebuild-platform`; it rebuilds only `platform-server`.

Do not combine host-run activation and an image rebuild just to duplicate evidence. Do not use
`docker compose down`, Maven `clean`, cache pruning or full-stack `--build` in the normal edit/test
loop. Preserve database volumes and healthy unchanged services.

### 2.2 Optional independent administrator control plane

The Admin API uses loopback port `9400`; the independent Admin Web uses loopback port `5174`. The API uses the
separate `spaceagent_admin` database and credential and never connects to `spaceagent_platform`.
Set the administrator credentials in the project's untracked `.env` before starting the Admin profile:

```dotenv
ADMIN_LOGIN=your-admin-login
ADMIN_PASSWORD=your-unique-strong-password
ADMIN_DISPLAY_NAME=Platform Administrator
```

Use 14+ characters containing uppercase, lowercase, digits and a symbol. These values must remain configured
on every startup. Docker Compose loads the root `.env` and injects them; a direct host/JAR launch must export
the same environment variables. Keep secrets out of Git and logs. Legacy `ADMIN_BOOTSTRAP_LOGIN/PASSWORD`
are fallback aliases, not one-time bootstrap values.

```bash
docker compose --profile admin up -d --build platform-admin-server admin-web
curl -fsS http://127.0.0.1:9400/actuator/health/readiness
curl -fsS http://127.0.0.1:5174/healthz
```

Admin V5 synchronizes the same singleton ID on startup. Unchanged configuration preserves sessions;
changed credentials or login name revoke old sessions and increment credential version. Missing/invalid
credentials fail startup. Login directly returns an Admin session after password verification, without
TOTP, recovery codes or mandatory password-change screens. To recover access, update the environment and
restart. Ordinary tenant credentials remain invalid here.

The refresh credential stays in an HttpOnly SameSite=Strict cookie; refresh/logout require matching
CSRF cookie and `X-Admin-CSRF`. Management commands still require a valid administrator session, existing
reason/confirmation/idempotency and audit evidence, but no MFA step-up. V5 invalidates legacy sessions;
deploy the new Admin backend and Admin Web together after backing up the Admin database. Do not roll back
to an old MFA binary against newly created password-only sessions. See ADR-087.

M40-PR3 exposes Dashboard, bounded global User/Organization pages and redacted Provider/Agent-key/
MCP inventory. Local HTTP between the two Java services requires
`SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL=true`; production must leave it false and supply an HTTPS endpoint
with a client certificate through the JVM trust/key-store configuration. Both services use the same
dedicated `SYSTEM_ADMIN_JWT_SECRET`, never the generic internal token. M40-PR4 adds authenticated,
reasoned and idempotent User commands; M40-PR5 adds configuration-gated durable User cleanup,
owner-module purge and tombstone execution. M41-PR1 Admin Web stores no access/refresh token and
proxies only `/admin/v1/**` to the Admin API.

## 3. Build and start platform-server when runtime activation is required

This is a standalone host-runtime path, not a mandatory completion step for every code change.

```bash
./mvnw -q -pl apps/platform-server -am package

SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5436/spaceagent_platform \
SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
PLATFORM_JWT_SECRET="$JWT_SECRET" \
PLATFORM_INTERNAL_TOKEN="$INTERNAL_SERVICE_TOKEN" \
PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY="$MODEL_PROVIDER_ENCRYPTION_KEY" \
PLATFORM_INFERENCE_EXECUTION_MODE=http \
PLATFORM_KNOWLEDGE_EMBEDDING_MODE=http \
java -jar apps/platform-server/target/platform-server-0.0.1-SNAPSHOT-exec.jar
```

Health: `curl -fsS http://127.0.0.1:9000/actuator/health`.

GitHub account and repository access uses the Marketplace/MCP path:

```bash
PLATFORM_GITHUB_MCP_CLIENT_ID=<dedicated GitHub App/OAuth App client ID>
PLATFORM_GITHUB_MCP_CLIENT_SECRET=<dedicated client secret>
PLATFORM_GITHUB_MCP_ALLOWED_REDIRECT_URIS=https://app.example/mcp/github/callback
PLATFORM_GITHUB_MCP_SCOPES=repo,read:user,read:org
```

OAuth authorization/token endpoints are not configurable: Tooling discovers and validates
them from the official MCP `WWW-Authenticate` and OAuth metadata chain.

For a non-GitHub OAuth-protected MCP server, configure one or more indexed pre-registered
clients. The authorization server and redirect are exact matches; both the MCP resource host and
authorization/token hosts must also be in `PLATFORM_TOOLING_MCP_ALLOWED_HOSTS`:

```bash
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_ID=acme
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_AUTHORIZATION_SERVER=https://login.acme.example/oauth
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_CLIENT_ID=spaceagent-client
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_CLIENT_SECRET=<dedicated-client-secret>
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_AUTHENTICATION_METHOD=CLIENT_SECRET_POST
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_SCOPES=tools.read,tools.call
PLATFORM_TOOLING_MCP_OAUTH_CLIENTS_0_ALLOWED_REDIRECT_URIS=https://app.example/mcp/oauth/callback
```

Begin through `POST /api/v1/mcp-marketplace/connections/{id}/oauth/begin`, complete through
`POST /api/v1/mcp-marketplace/oauth/complete`, then call the Connection qualification endpoint.
OAuth completion intentionally leaves the Connection `PENDING_VALIDATION`.

For the repository's local fake-provider smoke only, also set
`PLATFORM_INFERENCE_ALLOW_LOCAL_PROVIDER_HOSTS=true`. The broader
`PLATFORM_ALLOW_INSECURE_LOCAL=true` bypass is reserved for controlled migration
acceptance against an already-created development database and must never be used in
production.

## 4. Run the core acceptance smoke

In another terminal, with `INTERNAL_SERVICE_TOKEN` matching the server:

```bash
./scripts/smoke-platform-live.sh
```

The script registers two isolated tenants, verifies refresh replay rejection, creates a
Provider/Agent/Knowledge binding and Agent API key, exercises Conversation and Chat/SSE,
checks Runtime checkpoints/messages in PostgreSQL, and removes its temporary rows.
Without paid credentials it expects explicit errors from the real HTTP inference and
embedding adapters, never noop output.

## 5. Configure a real provider

Register/login through `/api/v1/auth/*`, then create a Provider using
`POST /api/v1/model-providers`. The host must appear in
`MODEL_PROVIDER_ALLOWED_HOSTS`; HTTP loopback is accepted only with the local-provider
flag. Create an Agent with `POST /api/v1/agents`, bind Knowledge document IDs, create a
Conversation at `/api/v1/chat/conversations`, and send Chat requests to
`/api/v1/chat/messages` or `/api/v1/chat/messages/stream`.

## 6. Optional compute services

Managed Artifact objects use `ARTIFACT_OBJECT_MODE=local` by default and store content-addressed bytes under the
existing persistent `/data/workspaces/artifact-objects` volume. Set mode `s3` only with an operator-controlled
S3-compatible endpoint, bucket, access key and secret key. Those credentials remain process configuration and are
never returned or persisted in Artifact metadata. The official MinIO Java SDK performs bounded staging/compose/
range reads; exact-object GET capabilities expire within 300 seconds. Public endpoints under
`/api/v1/artifact-objects` accept only SHA-256/size/MIME, base64 chunks, opaque owner IDs and revisions. They never
accept bucket names, object keys, endpoints, filesystem paths, lifecycle state or storage credentials.
Artifact staging reserves configurable per-user and per-tenant session/byte capacity before any bytes are created;
retained tenant bytes are capped as well. Storage publication preserves verified staging until the PostgreSQL
publish transaction succeeds, so an interrupted metadata commit can be retried without a split-brain object.
Knowledge URL refresh uses a 300-second renewable lease by default and fences the final Chunk/content activation.
An Artifact byte-deletion failure remains `BLOCKED`; only the administrator command endpoint
`POST /admin/v1/artifact-objects/{objectId}/deletion-retries` can requeue it after recent MFA,
with an explicit tenant, reason and idempotency key. There is no automatic blind retry.

M34 container sandbox mode uses Docker SDK for Python 7.2.0 and a disposable OCI container
for every Coding command. Java remains authoritative for every Run/Checkpoint/Tool ledger,
approval and Artifact:

```bash
SANDBOX_MODE=http docker compose --profile sandbox up -d --build \
  sandbox-worker platform-server
```

The worker has no published host port. Platform-server calls
`http://sandbox-worker:9200` with `PLATFORM_SANDBOX_INTERNAL_TOKEN`; Compose reuses the
internal service token unless a separate `SANDBOX_INTERNAL_TOKEN` is supplied. Optional
Linux gVisor deployments set `SANDBOX_OCI_RUNTIME=runsc` after installing that runtime in
the Docker daemon.

See `docs/architecture/SANDBOX-ISOLATION.md`.

Project intake analysis is deliberately unavailable in `in-process` mode. Set
`SANDBOX_MODE=http` and start the `sandbox` profile before calling
`POST /api/v1/projects/{projectId}/directories/{directoryId}/intakes`. The worker creates a
detached disposable worktree, mounts it read-only with network disabled, records bounded redacted
inspection evidence, and publishes a proposal. Blueprint/Task/TaskPlan rows are created only after
the owner confirms the exact `proposalHash`.

Autonomous PlanStep coding uses the same `SANDBOX_MODE=http` requirement. The Sandbox image now
installs the fixed `spaceagent-workspace-tool` entry point plus pinned `python-docx`, `pypdf` and
Beautiful Soup libraries. Java supplies the operation and bounded base64 input; neither the model
nor request selects an image, entry point, mount or network policy. Tune only worker scheduling with
`PROJECT_CODING_*`; changing those values does not weaken Tool/Governance/Workspace validation.

Start an approved Project plan with one reviewed execution binding:

```text
POST /api/v1/projects/{projectId}/tasks/{rootTaskId}/plans/{planId}/execute
{
  "projectDirectoryId": "...",
  "conversationId": "...",
  "sourceRepositoryId": "...",
  "agentId": "...",
  "reviewerAgentId": "...",
  "baseRef": "main"
}
```

The call is durably idempotent at the PlanStep/Agent binding. It activates an APPROVED plan,
materializes only the first dependency-ready Coding Job, and returns an existing active Job on
replay. The worker advances Step/Child/Root state and transactionally creates the next serial Job
after reviewed completion. Do not enqueue later steps from a browser scheduler.

Project execution handoff uses
`POST /api/v1/projects/{projectId}/task-plans/{planId}/steps/{stepId}/coding-jobs/{jobId}/handoffs`
with an `Idempotency-Key` and explicit target Conversation, target Agent and reviewer Agent.
Only same-directory paused/blocked jobs qualify. Query durable state under
`GET /api/v1/projects/{projectId}/run-handoffs`; the target job reuses the exact Workspace and a
separate fenced finalizer archives it only after reviewed completion.

The durable plan execution can also be queried directly by its owner:

```text
POST /api/v1/projects/{projectId}/task-plans/{taskPlanId}/executions
Idempotency-Key: <8-200 character opaque key>

GET /api/v1/projects/{projectId}/task-plans/{taskPlanId}/executions/{executionId}
GET /api/v1/projects/{projectId}/task-plans/{taskPlanId}/executions?page=1&pageSize=20
```

The POST body contains the reviewed directory, Conversation, source repository, root Task,
coding Agent, reviewer Agent and base ref. Authentication supplies tenant/user;
the server generates the execution ID. Execution status and active non-terminal CodingJobs are
Runtime projections; historical Jobs without `executionId` remain readable for compatibility.

## 6.1 TypeScript Multi-Agent service

M22 provides deterministic Supervisor/Specialist/Handoff/Reviewer LangGraph.js routing.
M28 adds opt-in Provider-backed decisions through a boundary-stepped `MODEL_REQUESTED`
command. Java performs that call through ModelPool, budget and ModelCallLedger; TypeScript
still performs no direct persistence, Provider-secret, Git, MCP or Tool side effect:

```bash
cd services/multi-agent-orchestrator
npm ci
npm test
npm run build
INTERNAL_TOKEN=<at-least-32-characters> npm start
```

Health is `http://127.0.0.1:9300/health`; orchestration uses
`POST /v1/orchestrate` with `contracts/multi-agent/v1`.

Enable deterministic TypeScript routing with:

```bash
PLATFORM_MULTI_AGENT_ORCHESTRATOR_MODE=http
PLATFORM_MULTI_AGENT_ORCHESTRATOR_BASE_URL=http://127.0.0.1:9300
PLATFORM_MULTI_AGENT_ORCHESTRATOR_INTERNAL_TOKEN=<same-internal-token>
PLATFORM_MULTI_AGENT_REASONING_MODE=deterministic
```

Set `PLATFORM_MULTI_AGENT_REASONING_MODE=provider` to enable M28 Provider reasoning. An
active visible `modelPoolRef` and at least 64 remaining tokens are required; otherwise the
deterministic policy remains in force. No Provider credential is configured in the
TypeScript process.

## 6.1.1 Reviewed source integration

M29 exposes the manual-first managed Git delivery flow:

- `POST /api/v1/projects/{projectId}/source-merges` with `Idempotency-Key`, `reviewId` and
  `commitProposalArtifactId` prepares one reviewed commit;
- `GET /api/v1/projects/{projectId}/source-merges/{mergeId}` reads durable state;
- `POST .../{mergeId}/apply` consumes exact `SOURCE_MERGE` Governance approval and performs
  Base-SHA-fenced local-ref integration;
- `POST .../{mergeId}/rollback` performs prepared-Commit-fenced local rollback;
- `POST .../{mergeId}/reconcile` resolves APPLYING/ROLLING_BACK/UNKNOWN from the actual ref.

All responses set `remoteUpdated=false`. The trusted client retrieves the reviewed Patch
Artifact and explicitly pushes/applies it; platform-server never pushes to GitHub in M29.

## 6.1.2 Trusted Beta release

M30 release assets are documented in
[`docs/operations/PRODUCTION-RUNBOOK.md`](operations/PRODUCTION-RUNBOOK.md) and
[`docs/operations/RELEASE-CHECKLIST.md`](operations/RELEASE-CHECKLIST.md).

```bash
cp .env.release.example .env.release
RELEASE_ENV_FILE=.env.release ./scripts/release-preflight.sh
docker compose --env-file .env.release \
  -f docker-compose.yml -f docker-compose.release.yml up -d --build
```

Use `/actuator/health/liveness` for process health and `/actuator/health/readiness` for
traffic admission. Trusted Beta readiness requires schema V1082 and a writable persistent
Workspace. Public untrusted-code execution is deliberately rejected.

## 6.1.3 Real external backend acceptance

Use the checked-in `spaceagent-real-e2e` Skill only after the operator explicitly authorizes
paid/external calls. Keep Provider credentials in the Git-ignored `testapikey` directory with
mode `0600`; never pass a GitHub account password to Git, MCP, a script, or a platform API.

```bash
.agents/skills/spaceagent-real-e2e/scripts/inspect_credentials.sh testapikey
.agents/skills/spaceagent-real-e2e/scripts/run_isolated_model_stack.sh testapikey
```

The runner builds the backend, creates an isolated PostgreSQL database and Workspace, and
executes real Provider connection, ModelPool, Embedding/RAG, Chat, SSE, health and usage
checks. Qwen is the authoritative path; the DeepSeek `/models` probe is optional and
non-blocking. Cleanup removes the generated database volume and temporary secret environment.
Only a mode-`0600`, redacted JSON evidence file and application log remain under
`.run/real-e2e/`, which is not committed.

GitHub acceptance is a separate phase. Run `inspect_github_prerequisites.sh` first. M31
defaults the Marketplace entry to `https://api.githubcopilot.com/mcp/`, discovers OAuth
metadata from the server challenge, and uses Spring Security Authorization Code + PKCE.
Official account/repository calls map `get_me` and `search_repositories`; private checkout
uses a consumed five-minute Tooling grant and never returns the OAuth token over HTTP.
Configure `GITHUB_MCP_CLIENT_ID`, `GITHUB_MCP_CLIENT_SECRET` and the exact comma-separated
`GITHUB_MCP_ALLOWED_REDIRECT_URIS`. HTTPS callbacks and explicit `127.0.0.1`/`::1` loopback
HTTP ports are accepted when allowlisted. Start browser login only from the platform-generated
authorization URL. Username/password and direct PAT configuration are rejected for the
official Marketplace profile. Custom endpoints retain the M26 `github_*` facade only for
compatibility.

Generic MCP OAuth uses RFC 9728 Protected Resource Metadata with well-known fallback, RFC 8414
then OIDC discovery, Spring Security Authorization Code + PKCE S256, and RFC 8707 `resource` on
authorization, exchange and refresh. Trusted Beta supports pre-registered clients only; Dynamic
Client Registration and Client ID Metadata Documents are deferred. Consumed PKCE transactions are
redacted and no OAuth completion bypasses Connection qualification.

## 6.1.4 Agent operational observability

The Java runtime always exposes Micrometer's Prometheus endpoint. The optional observability stack
adds Prometheus, Grafana, Loki, Tempo, Alloy and Alertmanager. OTLP trace export is deliberately
opt-in so a normal local start does not retry a missing collector:

```bash
PLATFORM_OBSERVABILITY_OTLP_ENABLED=true \
docker compose --profile observability up -d --build \
  platform-server prometheus alertmanager grafana loki tempo alloy
```

Open the private loopback endpoints:

- Grafana: `http://127.0.0.1:3000` (`SpaceAgent Agent Operations` and `JVM Overview`);
- Prometheus: `http://127.0.0.1:9090`;
- Alertmanager: `http://127.0.0.1:9093`;
- Tempo API: `http://127.0.0.1:3200`.

`/actuator/prometheus` includes JVM/HTTP/Hikari metrics plus low-cardinality Agent gauges derived
from the disposable Trace views. The Agent gauges contain no Organization/User/Agent/Run IDs or
payloads. In a multi-replica deployment they contain the same PostgreSQL projection, so dashboards
and alerts use `max` across instances rather than adding duplicate values.

Micrometer Tracing supplies trace/span correlation in logs. Set
`PLATFORM_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY` to the reviewed sampling rate and enable OTLP
only for a private trusted collector. The checked-in Alertmanager receiver keeps firing alerts
visible and silenceable locally but intentionally sends nothing outbound. Production must mount an
operator-reviewed `docker/observability/alertmanager.yml` containing its email/PagerDuty/webhook
receiver and secrets; never commit those credentials.

Operational telemetry is disposable. PostgreSQL AgentRun, RunEvent, ModelCall/Tool ledgers and the
existing owner-scoped Trace projection remain execution, recovery, billing and reconciliation
evidence if Prometheus, Tempo, Loki or Alertmanager is unavailable.

M52-PR2 records the first useful streamed Provider chunk exactly once on the active ModelCall claim.
The stored timestamp uses PostgreSQL clock; `firstChunkMillis` uses monotonic elapsed time measured
from Provider request dispatch. Trace/Prometheus expose it as TTFC and never estimate historical or
non-stream calls. Java propagates W3C Trace Context to the TypeScript orchestrator and Sandbox;
their official OpenTelemetry SDK exporters use the same default-off OTLP switch and endpoint.
Content attributes and business resource IDs are prohibited by ADR-052 and architecture checks.

## 6.2 Runtime coordination and event resume

M23 enables the PostgreSQL-backed continuation worker by default. Each replica needs a
distinct worker ID in production:

```bash
PLATFORM_RUNTIME_COORDINATION_ENABLED=true \
PLATFORM_RUNTIME_WORKER_ID="platform-server-a" \
PLATFORM_RUNTIME_LEASE_SECONDS=30 \
PLATFORM_RUNTIME_CONTINUATION_POLL_DELAY_MS=500
```

Worker Lease/Continuation operations are internal-token protected under
`/api/v1/internal/runtime/**`. User-visible durable events are available at
`GET /api/v1/runtime/runs/{runId}/events`; SSE uses
`GET /api/v1/runtime/runs/{runId}/events/stream` and accepts `Last-Event-ID`. The cursor is
the exclusive PostgreSQL RunEvent sequence, so reconnecting through another replica does
not depend on Redis or process-local emitter state.

## 6.3 Authoritative Automation

M24-PR3 enables the stateless Automation wake-up loop by default:

```bash
PLATFORM_AUTOMATION_ENABLED=true \
PLATFORM_AUTOMATION_POLL_DELAY_MS=1000
```

Schedules and occurrences remain in PostgreSQL. Spring parses cron, but PostgreSQL clock,
`SKIP LOCKED`, unique fire keys and Runtime Continuation own execution correctness. Every
authorized occurrence captures an immutable Run configuration snapshot and uses the Runtime worker ID/lease settings from
6.2. Disable the wake-up callback only for maintenance; do not replace it with Redis/Bull
or a browser timer.

User APIs are nested under
`/api/v1/agents/{agentId}/scheduled-tasks`. Manual triggers accept an optional
`Idempotency-Key` header. If Organization Governance requires Automation approval, the
execution remains `waiting_approval` until an OWNER/ADMIN decision is consumed.

## 7. Acceptance commands

```bash
./mvnw -q test
./mvnw -q package
./scripts/check-architecture.sh
docker compose config --quiet
```

Strict migration verification additionally requires the five database URLs documented
by `scripts/verify-m8-data-cutover.sh --help`.

## 8. Optional Web UI

The first-party React tenant client is maintained in `apps/web`.
Start its same-origin Nginx build with:

```bash
docker compose --profile web up -d web
```

Open `http://127.0.0.1:8080`. Nginx proxies `/api` to platform-server; local Vite development
targets `http://localhost:9000`. The tenant client includes first-party Overview/Tracing,
Organization, Agent Governance, Automation, Memory, Knowledge, Model Resources, MCP, Chat and
Project feature slices. Each mutation consumes an authoritative public Java contract; browser
state is presentation only.

Relevant backend Trace endpoints remain:

```text
GET /api/v1/users/tracing/traces
GET /api/v1/users/tracing/traces/{traceId}
GET /api/v1/users/tracing/stats
```

Trace queries require ACTIVE membership and return only the authenticated user's Runs in
the active Organization. The disposable views exclude prompts, checkpoints, tool/model payloads
and secrets. Settled calls expose cost when an immutable price exists; V1048 additionally exposes
the first useful streamed chunk latency recorded at the Provider boundary. Replay never fabricates
new timing evidence.

The active Web source is `apps/web` and has no shared compatibility-contract package. Build
it from the repository root with `npm run build`. Add authenticated features only against
the authoritative HTTP contracts documented in this file and the V2 architecture.

M37 browser auth uses `/api/v1/web/auth/*` and
`/api/v1/web/organizations/{id}/switch`. Refresh credentials are HttpOnly,
SameSite=Strict session Cookies scoped to `/api/v1/web`; browser responses never contain
them and React removes all legacy session/local-storage credentials. CLI clients retain the
original `/api/v1/auth/*` refresh-token JSON contract. Nginx emits CSP/HSTS/frame/referrer/
permission/COOP headers, and authenticated product routes are lazy chunks.

M67 closes the current public-contract parity batch. Project supports bounded multi-step TaskPlan
creation and server-derived PlanStep assignment evidence; Knowledge URL Job controls recover by
document after reload; Overview Sessions page through backend totals and link to matching Traces;
Organization Settings manages members/invitations and authenticated password changes. Missing
Agent-sharing, generic vault, attachment/context-selection, Event Trigger and Local Bridge browser
contracts remain explicitly non-interactive rather than simulated.

M68 aligns new ProviderModel form/Java fallback and Agent current-configuration defaults at `200000` context tokens.
Explicit ProviderModel limits remain unchanged. Applied V1003 backfill rows retain `32768` and their historical
configuration hash; do not edit that migration or reinterpret the independent maximum output-token limit.

## 8.1 Organization invitations

M25-PR1 adds backend-only Organization invitations. OWNER may invite ADMIN/MEMBER/VIEWER;
ADMIN may invite MEMBER/VIEWER. Create returns the plaintext token once and PostgreSQL stores
only its SHA-256 digest.

```text
POST   /api/v1/organizations/{organizationId}/invitations
GET    /api/v1/organizations/{organizationId}/invitations
DELETE /api/v1/organizations/{organizationId}/invitations/{invitationId}
POST   /api/v1/public/organization-invitations/preview
POST   /api/v1/organization-invitations/accept
```

Preview accepts `{ "token": "..." }` without authentication and returns only a masked
email. Accept requires authentication and the current user's normalized external identity
must equal the invitation email. Configure the default expiry with
`PLATFORM_IDENTITY_INVITATION_EXPIRATION_HOURS` (default 168, allowed 1-720). Email/token
delivery is caller-owned; never log or persist the returned plaintext token.

## 8.2 Organization cleanup control plane

M25-PR2B atomically enqueues a durable CleanupJob and 11 ordered CleanupSteps when the last
Organization member leaves. PostgreSQL owns retention, `SKIP LOCKED` claim, lease, fencing,
heartbeat, defer/retry and BLOCKED state.

```bash
PLATFORM_IDENTITY_CLEANUP_RETENTION_HOURS=24
PLATFORM_IDENTITY_CLEANUP_MAX_ATTEMPTS=10
PLATFORM_IDENTITY_CLEANUP_RETRY_BASE_SECONDS=30
```

M25-PR2C binds every ADR-025 step to its owner API and enables the PostgreSQL-only worker:

```bash
PLATFORM_IDENTITY_CLEANUP_WORKER_ENABLED=true
PLATFORM_IDENTITY_CLEANUP_WORKER_ID=cleanup-platform-a
PLATFORM_IDENTITY_CLEANUP_LEASE_SECONDS=60
PLATFORM_IDENTITY_CLEANUP_POLL_DELAY_MS=1000
```

Use a distinct worker ID per replica. Managed Git worktrees/mirrors are server-deleted;
Local Bridge roots remain client-owned and only their opaque server capability is removed.

## 8.3 Versioned Skill Registry

The backend exposes Organization-scoped Skill lifecycle routes:

```text
POST/GET /api/v1/skills
GET       /api/v1/skills/{skillId}
POST      /api/v1/skills/{skillId}/versions
POST      /api/v1/skills/{skillId}/versions/{versionId}/publish
POST      /api/v1/skills/{skillId}/versions/{versionId}/deprecate
DELETE    /api/v1/skills/{skillId}
```

Creating a Skill creates an immutable DRAFT version. Agent `skillIds` accept only the exact current
PUBLISHED SkillVersion ID in the same tenant. M55-PR2 compiles the exact pinned version into Chat
Planner, normal/planned Chat and Project Coding/Reviewer contexts. A Skill's required Tools must
already be enabled on the Agent current configuration. Skill text remains inert data: it cannot execute files,
grant Tool/MCP access or enter telemetry. Runtime checkpoints expose only SkillVersion IDs/hashes.

## 8.3.1 Agent current configuration

Creating an Agent immediately creates its single effective current configuration. Saving the Agent updates that
configuration with revision conflict protection and takes effect for future Runs without review or publication.
Every admitted Run copies a secret-free immutable configuration snapshot; later Agent edits cannot change historical
Run, Tool, Model, Checkpoint, Handoff or recovery evidence.

Organization collaboration does not create Agent versions. `GET /api/v1/agents` remains the caller's own list;
`GET /api/v1/agents/organization` returns active Agents in the current Organization with `DIRECT`,
`OWNER_APPROVAL_REQUIRED` or `READ_ONLY` write mode. A creator or current Organization OWNER receives the existing
direct `PUT/PATCH /api/v1/agents/{agentId}` response. An active ADMIN/MEMBER editing somebody else's Agent receives
HTTP 202 with `PENDING_APPROVAL`; the current configuration is unchanged until the current OWNER decides through:

```text
GET  /api/v1/agents/{agentId}/configuration-changes
GET  /api/v1/agents/{agentId}/configuration-changes/{requestId}
POST /api/v1/agents/{agentId}/configuration-changes/{requestId}/decision
```

The decision endpoint requires the exact request revision. Approval revalidates the Agent base revision/hash and all
references before one current-config CAS. Rejection, expiry, replacement and stale-base detection erase the proposal
body; retained hashes and actor/time fields are approval audit, not configuration history. The generic Governance
decision endpoint refuses this action so approval and Agent application cannot split across requests.

## 8.4 MCP Marketplace foundation

M26-PR1 exposes backend-only Marketplace APIs:

```text
GET  /api/v1/mcp-marketplace/catalog
GET/POST /api/v1/mcp-marketplace/installations
POST /api/v1/mcp-marketplace/installations/{id}/disable
GET/POST /api/v1/mcp-marketplace/connections
POST /api/v1/mcp-marketplace/connections/{id}/revoke
```

M50-PR1 adds versioned catalog reads while retaining every route above:

```text
GET /api/v1/mcp-marketplace/catalog/{entryId}/versions
GET /api/v1/mcp-marketplace/catalog/{entryId}/versions/{versionId}
```

Catalog Entries are stable identities; Publisher, ServerVersion and ordered remote Transport are
separate Tooling-owned records. Installation accepts an optional `serverVersionId`, defaults to the
current APPROVED Version and then keeps that pin. Reinstall never upgrades it implicitly. The old
catalog transport/auth/default-endpoint/manifest fields remain a rolling-client projection.

Configure auth-reference encryption with `PLATFORM_TOOLING_MCP_ENCRYPTION_KEY` and optional
`PLATFORM_TOOLING_MCP_PREVIOUS_ENCRYPTION_KEYS`. Connection responses never return auth.
Remote MCP execution is fail-closed to the comma-separated
`PLATFORM_TOOLING_MCP_ALLOWED_HOSTS` list (Compose: `MCP_ALLOWED_HOSTS`). The default permits
only the official GitHub hosts; add each reviewed custom MCP DNS name explicitly. Generic
`http_fetch` remains public-HTTPS capable and pins the exact validated DNS answer per request.

M26-PR2 adds the backend GitHub MCP discovery and OAuth routes:

```text
POST /api/v1/github-mcp/connections/{id}/oauth/begin
POST /api/v1/github-mcp/oauth/complete
GET  /api/v1/github-mcp/connections/{id}/repositories
POST /api/v1/github-mcp/discover
```

The connected server must expose `github_begin_oauth`, `github_complete_oauth`,
`github_list_repositories`, and `github_resolve_repository`. Production transport uses the
official MCP Java SDK Streamable HTTP client and accepts only public HTTPS endpoints. OAuth
state expires after ten minutes and is one-use; provider sessions/tokens are encrypted and
never returned by these APIs.

M26-PR3 adds ledgered Project source import:

```text
POST /api/v1/projects/{projectId}/sources/github-mcp
Idempotency-Key: <opaque key>
```

The JSON body contains `connectionId` and exactly one of `providerRepositoryId` or
`githubUrl`. The key is stored only as SHA-256. A matching terminal result is replayed;
different input conflicts, an active claim returns in-progress, and an indeterminate/expired
call becomes UNKNOWN. The SourceRepository response includes opaque `mcpConnectionId` and
`mcpInvocationId` evidence, never MCP auth or checkout credentials.

M26-PR4 enables private MCP SourceRepository Workspace provisioning through the internal
`github_prepare_checkout` tool. The MCP server must return the exact `cloneUrl`, a Basic or
Bearer `authorizationHeader`, and an `expiresAt` between three and ten minutes from issue.
There is no browser endpoint for this secret-bearing API.

Tooling encrypts the grant with `PLATFORM_TOOLING_MCP_ENCRYPTION_KEY`; Project never stores
it. Git receives the header only through its sanitized child environment. Workspace success
or failure consumes and clears the ciphertext. A PostgreSQL-clock redaction worker clears
orphaned expired grants; its default wake-up delay is 60 seconds and can be tuned with
`PLATFORM_TOOLING_MCP_CHECKOUT_REDACTION_DELAY_MS`.

Workspace provisioning commits PROVISIONING before MCP/Git work. A recovery worker marks
interrupted rows FAILED with revision CAS after 300 seconds (minimum 180), cleaning a partial
worktree first. Tune with `PLATFORM_WORKSPACE_PROVISIONING_STALE_SECONDS` and
`PLATFORM_WORKSPACE_PROVISIONING_RECOVERY_DELAY_MS`; keep the stale window above the bounded
Git timeout.

The `platform-server` runtime image must include `git` and `ca-certificates`, not only the
build stage. Managed repository mirror/fetch/worktree provisioning runs under its non-root
application user; Sandbox executes subsequent file/code operations against the shared volume.
An image readiness response alone does not prove Git provisioning: verify `git --version` and
an offline local mirror/worktree smoke in the final image when changing its runtime packages.
Run `bash scripts/check-platform-git-image.sh [image]` for this network-disabled disposable check.

Project workbench branch selection now creates self-contained local clones from the managed mirror,
without hardlinks or alternate object directories. The OCI child can inspect and commit using only
its exact Workspace mount. Legacy linked workspaces remain available for reading. Reviewed prepared
commits are staged locally into the source mirror before the existing ref CAS; this does not push
anything to a remote. See ADR-082 for conversation-first coding and workbench inspection APIs.

Existing named volumes retain their ownership across image rebuilds. Check the actual
`/data/workspaces/repositories` and `/data/workspaces/workspaces` subdirectories as the application
user, not only their parent. A root-owned non-writable worktree directory produces
`WORKSPACE_DIRECTORY_NOT_WRITABLE` before Git runs. Repair the exact affected directory using the
deployed application UID/GID; do not recursively change repository contents or erase volumes.

M31 adds the official GitHub Remote MCP profile. Its OAuth begin route requires a dedicated
GitHub App/OAuth App registration and returns a PKCE authorization URL discovered through
the MCP challenge metadata. Complete now accepts both `state` and the callback `code`:

```json
{"state":"<one-time-state>","code":"<authorization-code>"}
```

Access/refresh tokens remain encrypted in Tooling. Expiring tokens refresh with Connection
revision CAS. Official repository discovery is normalized from MCP text JSON; Project import
keeps the M26 Invocation Ledger. Git HTTPS checkout receives an in-process Basic header built
from the bound account login and OAuth token; the copied ciphertext is consumed afterward.

## 8.5 Provider health probes

M27-PR1 enables PostgreSQL-authoritative scheduled Provider `/models` probes. Configure a
distinct worker ID per replica:

```bash
PLATFORM_INFERENCE_HEALTH_PROBES_ENABLED=true
PLATFORM_INFERENCE_HEALTH_PROBE_WORKER_ID=provider-probe-a
PLATFORM_INFERENCE_HEALTH_PROBE_INTERVAL_SECONDS=300
PLATFORM_INFERENCE_HEALTH_PROBE_RETRY_BASE_SECONDS=30
PLATFORM_INFERENCE_HEALTH_PROBE_LEASE_SECONDS=60
PLATFORM_INFERENCE_HEALTH_PROBE_BATCH_SIZE=10
PLATFORM_INFERENCE_HEALTH_PROBE_POLL_DELAY_MS=1000
```

The callback is only a wake-up loop; PostgreSQL clock/claim/lease/fencing own correctness.
History is available at `GET /api/v1/model-providers/{providerId}/health-observations`.

M27-PR2 makes ModelPool `PRIORITY`, `WEIGHTED`, `COST` and `LATENCY` strategies executable.
Create immutable price windows at `POST /api/v1/provider-models/{providerModelId}/prices`;
cost routing fails closed without one effective price. Every fallback attempt is a distinct
ModelCallLedger row. UNKNOWN never advances to another Provider.

M27-PR3 adds optional Organization monthly hard limits:

```text
GET/PUT /api/v1/inference-budget/policy
GET     /api/v1/inference-budget/usage
```

OWNER/ADMIN manage policy. A zero limit means unlimited for that dimension. PostgreSQL
reserves worst-case request/token/cost before dispatch, settles actual usage, releases known
no-charge failures and retains UNKNOWN reservations. Cost policies require an effective
ProviderModel price and fail closed without it. Amounts use integer USD micro-dollars;
unpriced or incomplete calls stay unavailable rather than estimated.

## 8.6 Chat Root Tasks

M54-PR1 creates one CHAT-scoped Root Task for every ordinary Chat USER Message. The source Message
ID is the idempotency key; the Task goal is the exact bounded request. The Conversation focuses the
new Task and the AgentRun exposes an immutable `chatTaskId`. Successful responses return
`rootTaskId/rootTaskState=COMPLETED`; approval or UNKNOWN suspension returns the same ID in
`IN_PROGRESS`; terminal execution failure marks it FAILED.

`GET /api/v1/chat/conversations/{conversationId}/tasks?limit=100` returns the authenticated
owner's bounded newest-first history. A CHAT Root has a source Message; M54-PR2 additionally permits
Java-generated direct Child Tasks and a Conversation-scoped TaskPlan/PlanStep DAG. CHAT Tasks still
cannot have a Project or Workspace scope. Project Tasks and existing Project APIs remain unchanged.

Accepted LangGraph `PLAN_PROPOSED` commands are persisted through the Project Application API; the
model and TypeScript service never choose durable IDs. Owners may list/read and approve, activate or
cancel proposals at
`/api/v1/chat/conversations/{conversationId}/tasks/{rootTaskId}/plans`. V1050 serializes duplicate
source-Run proposals and preserves exact proposal hashes. Automatic invocation from ordinary Chat
and actual PlanStep execution were intentionally separated from M54-PR2.

M54-PR3A provides the default-off automatic review boundary:

```bash
PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED=true
PLATFORM_MULTI_AGENT_ORCHESTRATOR_MODE=http
PLATFORM_MULTI_AGENT_ORCHESTRATOR_BASE_URL=http://127.0.0.1:9300
```

An eligible non-Project Chat either continues normally when LangGraph returns `COMPLETED`, or
returns `WAITING_PLAN_APPROVAL` with `taskPlanId/taskPlanState=PROPOSED`. The same Run and assistant
reservation are preserved in `chat-plan-review/v1`; no normal inference or Tool effect has occurred.
M54-PR3A originally kept this switch outside Trusted Beta until the execution half was delivered.

M54-PR3B adds `POST /api/v1/chat/runs/{agentRunId}/resume-plan`. The exact plan must already be
APPROVED and ACTIVE. Runtime holds the PostgreSQL worker lease, starts one `chat-plan:{stepKey}`
RunStep per dependency-ready Step, and writes `chat-plan-step-completed` before moving on. Tool
approval/UNKNOWN checkpoints include `planProgress`; resume completes the interrupted Step and only
then continues the remaining DAG. Final synthesis completes the original reply, plan, Root Task and
Run. Trusted Beta may enable planning when `PLATFORM_MULTI_AGENT_ORCHESTRATOR_MODE=http`; default is
still false.

## 8.7 Runtime Tool suite

M33 exposes the executable backend catalog at `GET /api/v1/tooling/capabilities`. Agent
create/update requests select IDs through `enabledToolIds`; the Run configuration snapshot pins
that selection and Inference receives only the corresponding available JSON Schemas. There is
no public arbitrary Tool-execution endpoint: model Tool Calls execute inside the owning Java
AgentRun.

The catalog contains:

```text
echo
web_search  http_fetch  knowledge_search
file_read   file_list   git_status   git_diff
mcp_call    github_search_repositories   github_get_repository
document_read   document_write
document_workspace_read   document_workspace_list   document_workspace_write   document_workspace_delete
coding_write_file   coding_delete_file   coding_run_command
```

`web_search` is disabled by default. Configure an operator-controlled SearXNG instance whose
JSON API is reachable through public HTTPS:

```bash
PLATFORM_TOOLING_WEB_SEARCH_MODE=searxng
PLATFORM_TOOLING_WEB_SEARCH_BASE_URL=https://search.example.com
```

Compose maps the equivalent `TOOLING_WEB_SEARCH_MODE` and
`TOOLING_WEB_SEARCH_BASE_URL` variables. The endpoint must permit `format=json`; SpaceAgent
adds SafeSearch, rejects redirects/private-address DNS results, and bounds the response. Do
not place an API key in the base URL or Tool arguments.

`http_fetch`, Search, MCP and GitHub Tools require the pinned Agent's `networkEnabled=true`
and Governance `NETWORK_ACCESS`. Dynamic MCP arguments name an already-authorized
`connectionId` plus one currently advertised remote Tool; its schema and read-only annotation
are checked again immediately before call. The GitHub wrappers always use that Marketplace MCP
Connection and never accept a password or PAT.

File/Git/document/coding Tools are valid only for a Project Task-scoped Run and one READY,
writable `MANAGED_GIT` Workspace belonging to that Run. File reads are UTF-8 and bounded;
document extraction supports text/Markdown/HTML/PDF/DOCX/PPTX/XLSX/RTF/OpenDocument and
structured text through bounded Apache Tika parsing. Writes support only text, Markdown, HTML
and DOCX, require a matching extension, use an atomic replace and pass Governance. Coding
write/delete continues through bounded Project file operations. Coding commands keep the
existing Governance/Ledger/Checkpoint/Artifact path but M34 executes the allowlisted argv
inside the authenticated disposable-container sandbox; if container mode is unavailable the
command fails explicitly rather than running on the platform host.

Large reasoning models may take longer to return the first non-streaming Function Calling decision.
The default Provider read timeout is 120 seconds. Trusted deployments may set
`PLATFORM_INFERENCE_REQUEST_TIMEOUT_SECONDS` to a reviewed value from 1 through 600 seconds; the
ModelCall lease automatically includes its safety margin. A timeout after dispatch remains
`UNKNOWN` and is never automatically retried because the Provider completion outcome is ambiguous.

`POST /api/v1/chat/messages/stream` uses the Provider's OpenAI-compatible SSE stream. Runtime events,
`reasoning_delta`, answer `delta`, and `done` are separate events. Provider reasoning is ephemeral
presentation evidence and is not persisted to Conversation or Memory. When the first completion
requests Tools, Java executes them through the durable Tool ledger and performs one bounded second
model call with no Tools exposed; the second call's natural-language answer is the only Assistant
message persisted. Raw Tool JSON remains Runtime evidence.

When a policy requires human approval, M53-PR1 keeps the original Chat Run and assistant reply
reservation in `WAITING_FOR_USER`. The response exposes `executionState=WAITING_APPROVAL` plus the
exact pending Approval/Tool references; SSE ends with `suspended`, never a false `done`. After an
OWNER/ADMIN decision, resume the same Run with:

```text
POST /api/v1/chat/runs/{agentRunId}/resume-approval
{"approvalId":"..."}
```

Runtime restores the versioned `chat-approval/v1` checkpoint, acquires the PostgreSQL worker lease,
and retries only the same ToolCall ID with the exact approval capability. A matching terminal Tool
ledger row replays without consuming another approval.

M53-PR2 turns a Chat mutation with an ambiguous outcome into `WAITING_RECONCILIATION` plus a
`chat-tool-unknown/v1` checkpoint and exposes its immutable Tool revision. The owner may request
verification and same-Run continuation with:

```text
POST /api/v1/chat/runs/{agentRunId}/tools/{toolCallId}/reconcile
{"expectedRevision":2,"reason":"verified desired Workspace state"}
```

The reusable non-Chat endpoint is
`POST /api/v1/runtime/runs/{agentRunId}/tools/{toolCallId}/reconcile`. Both accept only revision and
reason: clients cannot choose status/result/evidence. Supported postconditions are
`document_write`, `workspace-write_file` and `workspace-delete_file`; verification performs only
bounded OCI Workspace reads. A mismatch or unsupported MCP/command Tool remains `UNKNOWN` and the
original effect is never dispatched again.

Non-Project documents use `/api/v1/document-workspaces` plus `/files/read`, `/files/list`, `/files/write` and
`/files/delete`; URL jobs use `/api/v1/knowledge/url-jobs`. USER scope equals the owner and ORGANIZATION scope
equals the tenant. Neither accepts a host path, Project, Task or Git binding. Document Workspace mutations are
limited to 500,000 UTF-8 bytes and use the same OCI/Governance/Tool Ledger path as Runtime tools. Reconcile an
UNKNOWN HTTP operation at `/api/v1/document-workspaces/operations/{toolCallId}/reconcile`; clients provide only a
new request ID and reason. The server derives scope and accepts only exact hash/size/existence proof.

M36 admission limits are intentionally non-authoritative: authentication uses bounded
process-local IP+subject windows and Chat/RunEvent SSE permits at most four active streams per
user within a 256-stream replica ceiling. Deployments still need edge/WAF limits across replicas.
JSON is rejected above 2 MB. Knowledge supplied content is capped at 1,000,000 characters,
retrieval at 64 documents, and processed documents at 512 Chunks. Workspace snapshots reject
more than 100 untracked files or a 2 MB Patch instead of truncating authoritative evidence.

## 8.8 Project Coding recovery packages

M51-PR2 exposes owner-scoped immutable recovery capture for a strict M51-PR1 Coding Run:

```text
POST /api/v1/projects/{projectId}/runs/{runId}/recovery-packages
Idempotency-Key: <8-200 character opaque key>

GET  /api/v1/projects/{projectId}/runs/{runId}/recovery-packages/latest
GET  /api/v1/projects/{projectId}/runs/{runId}/recovery-packages/{snapshotId}
```

Creation requires `SANDBOX_MODE=http` and a READY/DIRTY managed Workspace bound to the same
ProjectDirectory and PlanStep as the Run. Live HEAD/status/tracked Patch and each untracked Patch
are captured through allowlisted `git` argv in disposable OCI containers; there is no host fallback.
Patch content is capped at 1 MB, changed files at 100 and the durable canonical payload at 4 MB.
Any Sandbox failure, output overflow, in-flight effect or Run/Workspace revision drift aborts the
capture without a partial row.

The response combines the confirmed Blueprint, Root/Child Task, TaskPlan/PlanStep, Conversation
snapshot, Checkpoint/Event cursor, Artifact/Test/Acceptance evidence, selected Model attempts,
UNKNOWN Tool/Model effects and required Workspace approvals. It excludes Tool arguments/results,
Model responses, raw Checkpoint JSON and Approval operation hashes. `blockers` and `nextAction` are
safe recovery guidance; they never authorize or retry an effect. Every owner reference must be
revalidated before M51-PR5 hands the package to another Agent. M51-PR5 now performs that handoff;
unresolved UNKNOWN evidence blocks the target Job before a new model/tool effect.
