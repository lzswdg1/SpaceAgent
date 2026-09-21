# ADR-043: MCP Connection Qualification

- Status: Accepted / implemented in M50-PR2A
- Date: 2026-09-04

## Context

M50-PR1 pins each Installation to an immutable ServerVersion, but a Connection configured with an
endpoint and AuthRef previously became executable without proving that the endpoint speaks MCP or
recording the capabilities it exposed. A remote request cannot safely run inside a database
transaction, and its result must not activate a Connection that was reconfigured while the request
was in flight.

## Decision

Tooling and PostgreSQL remain the sole Connection and capability authority. V1040 adds
`PENDING_VALIDATION`, `DEGRADED` and `ERROR` Connection states, immutable capability snapshots and
ordered health observations. A new or reconfigured non-pending-auth Connection follows:

```text
configured -> PENDING_VALIDATION -> initialize + bounded tools/list
-> revision-fenced atomic snapshot/observation/ACTIVE transition
```

The official MCP Java SDK performs initialize and paginated Tool discovery outside a database
transaction. Protocol/server metadata, declared capabilities, Tool schemas and annotations are
validated and bounded before persistence. Credentials, request arguments, Tool results and remote
error text are never stored in the snapshot or returned by these APIs.

Completion uses the Connection revision as a compare-and-set fence. Success atomically inserts the
snapshot and observation, activates the Connection and resets failure evidence. A failed initial
probe becomes `ERROR`; a failed requalification of an active Connection becomes `DEGRADED`. Only
`ACTIVE` Connections may list or call Tools. Any Connection/auth reconfiguration invalidates the
current snapshot and qualification metadata.

Existing V1039 `ACTIVE` Connections remain active during the migration so a release does not
silently disable already-installed integrations. The specialized GitHub OAuth completion path may
also retain its verified activation behavior; it is not generalized into arbitrary OAuth.

## Consequences

- Runtime Tool use now has a positive remote-protocol gate for new/reconfigured Connections.
- Capability evidence is immutable, tenant-authorized, revision-bound and safe to inspect without
  exposing AuthRefs.
- Stale remote responses cannot activate or overwrite a newer Connection configuration.
- V1040 becomes the Trusted Beta release-readiness schema; earlier Flyway migrations remain
  immutable.
- Generic OAuth discovery/callbacks, scheduled health workers, multi-account Connections,
  AgentVersion MCP binding, Registry synchronization and Resources/Prompts/Tasks remain deferred.
