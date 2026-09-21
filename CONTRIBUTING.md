# Contributing to SpaceAgent

SpaceAgent is a self-hosted **developer Beta**. Contributions are welcome, but the public APIs,
deployment profiles, and user experience may change between Beta releases. Please search existing
issues and discuss broad architecture, security, persistence, or cross-runtime changes before
investing in a large implementation.

Participation is governed by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report vulnerabilities
privately as described in [SECURITY.md](SECURITY.md), never in a public issue.

## Repository topology

- `apps/platform-server`: authoritative Java business backend.
- `apps/platform-admin-server`: independent administrator control plane.
- `apps/web` and `apps/admin-web`: React/TypeScript clients.
- `services/multi-agent-orchestrator`: optional TypeScript/LangGraph.js compute process.
- `workers/sandbox-worker`: optional isolated Python execution worker.
- `cli`: Go command-line client.

Java and PostgreSQL own authoritative business state. Compute workers must not take ownership of
Project, Task, Conversation, Memory, Provider secrets, Git effects, or Tool effects.

## Prerequisites

- Java 21
- Go 1.23+
- Node.js 22+
- Docker Engine 26+ and Docker Compose

## Local setup

```bash
git clone https://github.com/lzswdg1/SpaceAgent.git spaceagent
cd spaceagent
cp .env.example .env
make dev
```

Replace the placeholder secrets in `.env` before starting shared or release-like
environments.

Run the Java backend directly when required:

```bash
./mvnw -pl apps/platform-server -am spring-boot:run
```

Build the CLI and Web client:

```bash
make build-cli
npm ci
npm run build
```

Additional component-specific commands and supported profiles are documented in `docs/DEVELOPMENT.md`
and the component manifests. Do not use real Provider keys, GitHub accounts, or production data for
ordinary contribution tests.

## Make a focused change

- Keep the repository buildable and avoid unrelated rewrites.
- Preserve module boundaries in `docs/architecture/DEPENDENCY-RULES.md`.
- Add deterministic regression coverage for new behavior and bug fixes.
- Never weaken tenant/owner authorization, audit and effect ledgers, `UNKNOWN` semantics, or sandbox
  isolation to make a test pass.
- Do not edit an applied Flyway migration; add a new migration when a schema change is required.
- Do not commit credentials, `.env`, `.run`, `output/`, build artifacts, private data, database dumps,
  unredacted logs, or IDE state.
- Record the source and license of copied, generated, vendored, or externally derived material.
  Material with uncertain provenance is not acceptable in a public contribution.

## Validation

Run the smallest deterministic checks that prove the changed behavior. Examples include:

```bash
./mvnw test
cd cli && go test ./... && go vet ./...
npm test
npm run build
scripts/check-architecture.sh
git diff --check
```

Do not run paid Provider calls, live GitHub OAuth, production operations, or destructive acceptance
tests as part of an ordinary pull request. State exactly which checks ran, their result, and why any
relevant check was omitted. Full-stack rebuilds are expected only when the affected release surface
requires them.

Update an authoritative architecture or operations document only when its durable contract changes.
Routine fixes do not need to rewrite historical milestone journals.

## Pull requests

- Keep changes scoped and commits reviewable.
- Explain user-visible behavior, ownership boundaries, migrations, compatibility, and rollback risk.
- Complete the pull request checklist and respond to review with focused follow-up commits.
- Document any separately authorized live Provider or GitHub acceptance apart from deterministic
  evidence.

## Developer Certificate of Origin and license

SpaceAgent uses the [Developer Certificate of Origin 1.1](https://developercertificate.org/) (DCO),
not a Contributor License Agreement. Sign off every commit with:

```bash
git commit -s -m "type(scope): concise description"
```

The sign-off certifies that you have the right to submit the contribution under the project's
license. Contributions are accepted under the repository's MIT license (`inbound = outbound`), and
contributors retain copyright in their work.

If a commit is missing its sign-off, amend or rebase it before review. Do not sign on behalf of
another contributor without explicit authorization.

## Third-party material

Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before adding a dependency, container image,
copied implementation, fixture, font, icon, or generated asset. Include upstream provenance,
version or commit, license, and required notices in the pull request. A package name, public URL, or
technical ability to copy content is not proof of redistribution permission.
