# ADR-045: Official MCP Registry is reviewed external metadata

- Status: Accepted
- Date: 2026-09-05
- Milestone: M50-PR3

## Context

SpaceAgent's M50 Marketplace already owns stable Entries, immutable ServerVersions, ordered remote
Transports and version-pinned Installations. The official MCP Registry offers a public discovery API,
but its records are supplied and updated outside SpaceAgent. Reading that API in an installation or
Tool-execution path would make external availability and mutation part of local business authority.
Automatically activating synchronized records would also turn metadata ingestion into remote code or
credential execution without a platform review decision.

## Decision

Tooling consumes only the fixed official Registry origin. A private SystemAdministrator command
enqueues a durable synchronization Job. A leased worker requests cursor pages using the last
successful Job watermark, with strict page, record, response, manifest, timeout and redirect bounds.
Network I/O runs without an open database transaction.

A successful Job atomically stores deduplicated, secret-sanitized immutable Snapshots and separate
`PENDING_REVIEW` Candidates. It does not create an Installation, Connection or executable Tool.
Malformed required identity fails the Job without advancing the watermark. Repeated cursors and
exceeded bounds also fail with safe codes only.

Only a recent-MFA SystemAdministrator using exact internal scopes may approve or reject a Candidate.
Approval requires an active upstream record and at least one fixed public HTTPS `streamable-http`
remote. It atomically creates or advances the local Publisher/Entry/ServerVersion/Transport model and
marks the Candidate approved. Existing local versions are immutable: the same registry name/version
with another manifest digest is a conflict. Installations continue to pin only local approved
ServerVersions.

Package, stdio, SSE, URL-template and non-HTTPS records remain reviewable evidence but cannot be
approved by this milestone. Header values and fields marked secret are removed before persistence;
Registry data never becomes a stored Provider/MCP credential.

## Consequences

- PostgreSQL V1042 adds Registry source, Job, Snapshot and Candidate authority under Tooling.
- `platform-admin-server` remains a separate identity/command/audit plane and accesses the feature
  only through exact-scope HTTP; it receives no platform database credential.
- Registry outages cannot break catalog reads, existing Installations or Tool execution.
- Upstream deprecation/deletion is evidence for review, not an automatic local revocation.
- Arbitrary registries, upstream publishing, automated security scoring and local package execution
  require separate future decisions.
