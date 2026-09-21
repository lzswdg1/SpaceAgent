# Environment and execution boundaries

## Default local runtime

The complete default Compose stack contains `postgres`, `database-init`, and `platform-server`.
For daily source iteration, start only the reusable dependencies and run or test the changed Java
application on the host:

```bash
docker compose up -d postgres database-init
```

Do not rebuild or restart `platform-server` merely because source changed. Follow
[runtime-feedback-loop.md](runtime-feedback-loop.md) when a running process or image proof is
actually required.

Optional profiles:

- `web`: React Nginx build;
- `observability`: Prometheus, Grafana, Loki, Tempo and Alloy;
- `sandbox`: private Docker/OCI Sandbox Worker.
- `admin`: loopback-only independent SystemAdministrator identity/security service and
  `spaceagent_admin` initialization.

Redis and Kafka are not active runtime dependencies. PostgreSQL `spaceagent_platform` is the
durable business/Runtime source of truth. Git/worktrees own source state; external object storage
is reference-only when configured.

## Language/process boundaries

- Java `platform-server`: public API, business lifecycle, authorization, secrets, Runtime,
  ModelCall/Tool ledgers, Git and side-effect authority.
- Java `platform-admin-server`: administrator identity/password/TOTP/session, command journal,
  audit and redacted HTTP read composition; no platform business DB, Provider/MCP secret or tenant identity.
- TypeScript `multi-agent-orchestrator`: ephemeral graph reasoning only; disabled unless Java HTTP
  mode is explicitly configured.
- Python `sandbox-worker`: Docker/OCI execution only. It has no business DB client, Provider key,
  durable checkpoint, or process-execution fallback.
- Java in-process Sandbox supports deterministic `echo/fail` for focused tests only and must not
  execute Coding commands.

## External integration boundaries

- GitHub account/repository access is Marketplace MCP only. Official OAuth uses configured client
  credentials and exact callback allowlists; no password, PAT fallback, or Native Project OAuth.
- Web Search requires an operator-controlled SearXNG JSON endpoint. Missing configuration is an
  explicit unavailable result, not fake data.
- User Provider keys enter Java through Provider APIs, are encrypted, and never enter TypeScript,
  browser storage, logs, Git or evidence files.
- Network tests, paid models, GitHub OAuth and live accounts require explicit authorization and
  `spaceagent-real-e2e`.
- System Administration reads require dedicated <=60-second exact-scope JWTs plus mTLS. The generic
  internal token is rejected. HTTP is allowed only by the explicit local-development switch.

## Configuration sources

- `.env.example`: local template without real credentials;
- `.env.release.example`: Trusted Beta release template;
- `.env`/`.env.release`: ignored, user-owned secrets;
- `testapikey`: ignored live-test input, never inspect or print values outside real-E2E workflow;
- `.run`: ignored ephemeral evidence and temporary state.

Production/Trusted Beta rejects development secrets, noop inference, deterministic embedding,
public-untrusted code and unsafe temporary Workspace roots. M55-PR1 advances the platform schema
to V1051 for scoped Chat TaskPlan proposals plus the versioned Skill Registry; the release overlay explicitly enables the otherwise-default-
off User deletion executor. Automatic Chat planning remains default-off; after M54-PR3B Trusted
Beta may enable it only with `MULTI_AGENT_ORCHESTRATOR_MODE=http` and an explicit reachable URL.
Tooling owns Publisher/ServerVersion/remote Transport metadata and every Installation pins one
approved immutable MCP ServerVersion. External Registry metadata must be snapshotted into
PostgreSQL before use and must never become a live business dependency. Official Registry HTTP is
fixed to `https://registry.modelcontextprotocol.io`, cursor/page/body/result bounded and
redirect-free. Synchronization runs outside database transactions; successful ingestion creates
secret-sanitized immutable evidence and `PENDING_REVIEW` candidates only. Package/stdio/SSE,
template-only and non-HTTPS transports are not executable Marketplace versions. Publication
requires an exact-scope, recent-MFA SystemAdministrator approval through the independent Admin
command journal.
New or reconfigured remote Connections remain `PENDING_VALIDATION` until the official MCP SDK
completes initialize and bounded Tool discovery. Capability snapshots and health observations are
secret-free; only `ACTIVE` Connections can list or call Tools. Deterministic verification uses a
loopback fixture, never a public MCP server or user account.
Generic MCP OAuth is disabled when no pre-registered client profile is configured. Resource and
authorization/token hosts must be reviewed in the MCP host allowlist, issuer and redirect matches
are exact, and client secrets/tokens/PKCE never enter browser state, logs, Git or evidence.
Project execution uses logical relative ProjectDirectory identities, never browser-provided absolute
paths. Only a SourceRepository root may bind an executable Workspace in M51-PR1. A new Coding Run
pins its ProjectDirectory and Workspace; commands remain OCI-Sandbox-only, and later M51 work moves
the remaining Project byte/Git/document transports behind the same binding without passing Provider
or MCP credentials into the Sandbox.
M51-PR2 Project recovery capture reads live Git evidence only through that exact OCI Workspace.
It persists a Runtime-owned immutable, content-hashed recovery payload containing bounded source
evidence and owner-module references; it never sends credentials to the worker or treats the
snapshot as current lifecycle authority.
M51-PR4 requires `platform.sandbox.mode=http` for every active Workspace File/Document/Git/Coding
operation. The immutable image supplies `spaceagent-workspace-tool`; Java provides bounded base64
input and retains Tool/Governance/Runtime/Artifact authority. In-process mode advertises Workspace
tools unavailable and does not start the Project Coding worker.
M51-PR5 reuses that exact Workspace for a cross-Conversation/Agent continuation. Project handoff
finalization is PostgreSQL leased/fenced, writes bounded Project Memory through the owner API and
archives only a completed managed Workspace; it never updates a remote Git ref.
M52-PR2 pins the Development OpenTelemetry GenAI subset to upstream commit `94f432d7`. OTLP is
default-off in Java, TypeScript and Python. Private Java HTTP clients propagate W3C context;
workers extract it with official SDKs and emit no content/resource-ID attributes. ModelCallLedger,
not Tempo, owns the first useful streamed-chunk timestamp and monotonic dispatch duration.
M55-PR2 resolves only the SkillVersion IDs bound by the Agent current configuration and copies those
exact bindings into the immutable per-Run
configuration snapshot. Chat/Project model context may contain bounded Skill instructions, while
Runtime evidence and OpenTelemetry contain only IDs/hashes. Skill metadata never grants
Tool/MCP/network/Workspace authority.
The Admin Flyway source remains independently versioned through V4 inside `spaceagent_admin`; it
does not change the platform release schema version. V4 enforces at most one SystemAdministrator.
Bootstrap is zero-to-one and requires eight recovery codes; the offline break-glass runner requires
an exact confirmation plus a unique request ID, revokes all Sessions, and rejects replay. Both modes
must be disabled and their raw password/TOTP/recovery-code environment values cleared immediately
after success. Online creation/suspension/restoration/credential recovery of Admin principals is
not a supported lifecycle.
