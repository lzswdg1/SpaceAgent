# SpaceAgent V2 Public Refactor Summary

> Status: COMPLETE
> Historical private implementation log: OMITTED_FROM_PUBLIC_SNAPSHOT

## Outcome

The active product is a modular Java/PostgreSQL platform with an independent administrator control
plane, two React clients, a Go CLI, a compute-only TypeScript Multi-Agent process and an optional
Python Docker/OCI Sandbox Worker. Retired Java runtimes and the previous Python orchestration path
are not distributed in this public snapshot.

## Durable decisions

- Java and PostgreSQL own business, Runtime, authorization, credential, ledger and recovery state.
- TypeScript and Python remain replaceable compute and own no business persistence or Provider/MCP
  secret.
- Modules communicate through public APIs; shared code has no business-module dependency.
- Applied Flyway migrations are immutable and schema upgrades are forward-only.
- Tool, Provider, MCP, Git and storage effects preserve idempotency, audit and `UNKNOWN`
  reconciliation.
- Agent uses one mutable current configuration; each Run stores an immutable configuration
  snapshot.
- Project work is directory/workspace scoped and every active file/Git/command effect crosses the
  authenticated OCI Sandbox boundary.
- Telemetry is redacted and non-authoritative.

## Completed capability groups

1. Identity, Organization membership and tenant authorization.
2. Provider, ModelPool, Agent current configuration and immutable Run snapshots.
3. Chat, Task/TaskPlan, Project execution, approval, recovery, review and handoff.
4. Tool/Skill/MCP Marketplace, OAuth, Governance and durable effect ledgers.
5. Knowledge/RAG, Memory, Artifact, Automation and Observability.
6. Independent administrator identity, commands, audit and redacted business evidence.
7. React tenant/admin clients, Go CLI and deterministic cross-runtime contracts.

## Current restrictions

- Public-untrusted execution, production gVisor acceptance, high availability and external
  Provider/GitHub acceptance are not certified.
- The Sandbox controller and Alloy Docker discovery have Docker API access and must remain private.
- The public repository identity and release-signing permissions remain unresolved external setup.

Detailed current behavior lives in `docs/architecture/`, `docs/DEVELOPMENT.md`,
`docs/operations/` and `.agent/CURRENT.md`. This file intentionally contains no private branch,
commit, tag or checkpoint identifiers.
