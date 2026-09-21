# Current repository map

Use this map to locate ownership; verify details in source before changing behavior.

| Path | Current role |
| --- | --- |
| `apps/platform-server` | Java 21/Spring Boot modular business and Runtime authority |
| `apps/platform-admin-server` | Independent Java SystemAdministrator identity/security service; separate Admin DB, no platform business authority |
| `apps/web` | React/TypeScript same-origin browser client |
| `cli` | Go CLI using public platform HTTP contracts |
| `services/multi-agent-orchestrator` | TypeScript + LangGraph.js compute-only Supervisor/specialist/reviewer boundary |
| `workers/sandbox-worker` | Python Docker/OCI control plane; no host-process executor |
| `shared/shared-kernel` | Minimal technical Java primitives only |
| `contracts/multi-agent/v1` | Java/TypeScript Multi-Agent schemas and fixtures |
| `contracts/platform-admin/v1` | Versioned redacted Admin read wire schemas |
| `contracts/proto/execution/v1` | Java/Python OCI Sandbox wire contract |
| `scripts` | Active regression, release, backup, diagnostics and deterministic fixtures |
| `.agent` | Durable milestone plan, handoff and status |
| `.agents/skills` | Project-specific Codex workflows |

## Java module shape

Business modules live under `com.spaceagent.platform.<module>` with:

```text
api -> application -> domain <- infrastructure
```

`integration` hosts public/internal HTTP adapters. It may coordinate public Application APIs but
must not own another module's data. Current modules: identity, agent, inference, project,
conversation, context, memory, knowledge, runtime, tooling, artifact, governance, automation,
observability, integration and shared.

`apps/platform-admin-server` is a separate deployable and Maven module. Its `identity`, `command`
and `audit` slices own only `spaceagent_admin`. Its `platformclient` calls exact-scope private
owner APIs and keeps only an ephemeral stale Dashboard snapshot. Never import `com.spaceagent.platform.*`, connect it
to `spaceagent_platform`, or pass Provider/MCP encryption keys to it. Platform-wide management
reads and writes must use the versioned private contract. M40-PR3 reads, M40-PR4 User commands and
M40-PR5 durable User cleanup are implemented.

## Removed paths that must not return

- `backend` and retired Java microservices;
- `services/ai-orchestrator` and the old Java/Python orchestration v1 contract;
- Project-owned Native GitHub OAuth/API/secret repositories;
- Sandbox host-process executor;
- Redis/Kafka active Compose services and old Gateway/Channel CLI;
- `.kiro` and completed refactor-driver scripts.

`copy/browser` is an isolated inactive private reference snapshot, not a build/runtime dependency.
It is excluded from public-source exports because its redistribution rights are not established.
