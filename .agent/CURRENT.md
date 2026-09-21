# SpaceAgent Public Current State

> Updated: 2026-09-21
> Status: SOURCE_QUEUE_COMPLETE_WITH_EXTERNAL_GATES
> Active milestone: NONE
> Next backend milestone: NONE
> Authoritative backend queue: `.agent/BACKEND-PLAN.md`
> Public repository: `https://github.com/lzswdg1/SpaceAgent`

## Snapshot purpose

This directory is a history-free public-source candidate. It contains current source, migrations,
contracts, deterministic tests and public architecture/operations documentation. Private repository
branches, commit identifiers, internal checkpoints and legacy Git references are intentionally not
part of the public handoff.

## Implemented topology

- Java 21 `apps/platform-server`: tenant/business APIs, authorization, Runtime, durable ledgers,
  Provider/MCP/Git effect authority and PostgreSQL migrations.
- Java 21 `apps/platform-admin-server`: one non-tenant SystemAdministrator, isolated Admin database,
  command journal, audit and redacted platform-client composition.
- React/TypeScript `apps/web` and `apps/admin-web`: presentation and transient browser state only.
- Go `cli`: public platform HTTP client published as `github.com/lzswdg1/SpaceAgent/cli`.
- TypeScript `services/multi-agent-orchestrator`: ephemeral LangGraph.js proposals only.
- Python `workers/sandbox-worker`: authenticated Docker/OCI execution control plane only.
- PostgreSQL `spaceagent_platform` and `spaceagent_admin`: authoritative durable stores.

## Implemented product areas

Identity/Organization, Agent current configuration, Provider/ModelPool/budget, Conversation/Chat,
Project/Directory/Task/Plan/Workspace, durable Runtime/recovery/handoff, Memory, Knowledge/RAG,
Tool/Skill/MCP, Governance, Automation, Artifact and redacted Observability are present in the active
tree. Milvus and pgvector are deployment-selected Knowledge index backends.

## Security posture

- Real `.env` files, credential directories, certificates, backups, databases and generated build
  output are excluded from the candidate and Docker/export contexts.
- Provider and MCP credentials stay encrypted in Java/PostgreSQL and do not enter browser storage,
  TypeScript orchestration, Sandbox child containers, telemetry or Git evidence.
- The optional Sandbox Worker owns a writable Docker API connection and is therefore trusted
  infrastructure with host-root-equivalent blast radius if compromised. Its profile is default-off,
  has no published port and is not certification for public-untrusted code.
- Telemetry is redacted and disposable. Java/PostgreSQL ledgers remain recovery, billing and effect
  truth.

## Public release state

- Asset origin and redistribution permission are recorded in `ASSET-PROVENANCE.md`.
- `copy/browser` is absent and must remain absent.
- `PUBLIC-SOURCE-MANIFEST.txt` is intended to be committed in the future public repository.
- Deterministic CI/release gates cover Java, both Web clients, Go, TypeScript orchestration, Python
  worker, exporter, secret scanning, Dockerfile checks, Compose parsing and SBOM generation.
- No legal review, penetration test, production HA certification or public sandbox certification is
  claimed.

## Deterministic public-candidate evidence — 2026-09-21

- Gitleaks v8.28.0 scanned the exact candidate with zero findings; custom credential, privacy,
  workstation-path and private-history checks passed.
- Maven `validate`, `verify` and `package` passed: 909 tests, zero failures/errors and six optional
  real Tika/Milvus cases skipped because their local test URIs were not configured.
- Tenant Web passed 149 tests and production build; Admin Web passed 34 tests and production build.
- Go CLI test/vet passed; Multi-Agent Orchestrator passed 15 tests and build; Python 3.12 Sandbox
  Worker passed 29 tests after installing its declared dependencies.
- Architecture, Admin release positive/negative tests, low-resource guard, public exporter, supported
  Compose combinations, five Dockerfile BuildKit checks and production npm audits passed.
- GoReleaser v2.18.2 validated the configuration in an isolated temporary Git fixture. The public
  candidate itself remains uninitialized and has no `.git` directory.

## Remaining operator work

1. Configure CODEOWNERS, private vulnerability reporting,
   branch protection, required checks, maintainer permissions, dependency updates, DCO enforcement
   and release-signing/attestation permissions.
2. Run the exact clean-snapshot gate, review generated dependency/image SBOMs and perform any
   separately authorized external acceptance.

## Prohibited assumptions

- Do not run paid Provider, live GitHub OAuth/MCP, production database or destructive operations
  without explicit authorization.
- Do not restore removed runtimes, `copy/browser`, host-process Sandbox execution, Redis/Kafka
  authority or cross-module persistence access.
