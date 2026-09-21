---
name: spaceagent-verification
description: Plan and run risk-proportional deterministic SpaceAgent checks, from a closest-test Fast Path to full release gates. Excludes paid Provider calls and live GitHub OAuth, which require spaceagent-real-e2e.
---

# SpaceAgent Verification

Verification proves the changed behavior without turning every edit into a release rehearsal. Read
`AGENTS.md`, the diff, and [validation-matrix.md](references/validation-matrix.md), then select the
smallest evidence set that covers the actual changed surfaces.

## FAST verification

Use for local fixes, private deletion, documentation, Skill, test, configuration-example, and
contract-preserving refactors.

- Run the closest unit, slice, syntax, type, or structural check.
- Check direct references and run `git diff --check`.
- Do not run full Maven, all Compose profiles, migrations, packaging, or rebuild services unless
  the changed surface requires them or the user explicitly requests them.
- Documentation and Skill-only changes require structural validation, not product regression.

## STANDARD verification

Use for a bounded supported change owned by one module.

- Run affected domain, application, adapter, and controller tests.
- Run PostgreSQL tests only for SQL, mappings, transaction behavior, or migrations.
- Run the production HTTP entry point only for HTTP, security-chain, serialization, or wiring changes.
- Run package or profile validation only for build, dependency, configuration, or startup changes.
- Run non-Java checks only for changed non-Java components.

## STRICT verification

Use for authority, security, destructive persistence, durable effects, cross-runtime contracts,
sandbox boundaries, release, deployment, or explicitly requested acceptance.

- Run the focused union once after implementation stabilizes.
- Run the full Maven and affected non-Java suites once when closure requires repository-wide proof.
- Run package, architecture, migration, security, and affected Compose gates once at closure.
- Use the real-e2e Skill only with explicit authorization for paid Providers, live GitHub OAuth, or
  other external systems. Deterministic proof never implies live external acceptance.

## Rerun rules

- Production code, migration, shared test setup, dependency, or runtime wiring changes invalidate
  evidence that exercised the previous implementation and require the affected broader rerun.
- A fixture-only or assertion-only repair may rerun the failed set when production code and the
  previously executed production path remain unchanged.
- Documentation and status edits do not invalidate product-test evidence.
- Exact aggregate test totals are optional outside release closure. Do not mine stale reports merely
  to report a ceremonial number.

## Runtime activation

Rebuild or restart local services only when artifacts or runtime configuration changed and activation
is requested or necessary to verify the behavior. A source-only or Skill-only change does not need a
container rebuild.

- Source tests and a running-stack smoke are different evidence; neither automatically requires the
  other.
- Reuse healthy dependency containers and preserve their volumes. Do not use `docker compose down`,
  Maven `clean`, Docker cache pruning, or unrelated service restarts as routine setup.
- For environment-only changes, recreate only the affected service with its existing image.
- For image or packaged-source changes requiring container proof, rebuild only the affected service.
- Full-stack or multi-profile rebuild is a release or cross-service-contract gate, not an ordinary
  maintenance gate.

## Safety and reporting

- Preserve unrelated work and never stage `output/` or credentials.
- Do not use destructive database cleanup for ordinary verification.
- Record which surfaces were covered, the commands and outcomes, and which higher gates were not run.
- Never report `COMPLETE`, release-ready, or production-ready beyond the evidence actually obtained.
