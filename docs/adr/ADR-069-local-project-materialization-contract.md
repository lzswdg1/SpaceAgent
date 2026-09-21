# ADR-069: Local Project Materialization Contract

- Status: Accepted
- Date: 2026-09-08
- Scope: M60-PR1-U01

## Context

`LocalWorkspaceBridge` deliberately stores only an opaque root handle, bridge identity,
device metadata and a hash of its Bridge credential. The CLI/Desktop bridge alone knows a
user-authorized local absolute path. Project intake and Coding only accept server-managed
Source and Workspace metadata; the server must never reinterpret a client string as a local
filesystem path.

M60 needs Local Project source to enter the same isolated Sandbox flow as a remote source,
without restoring a server-side local-path reader, a browser-controlled path, or an
unledgered host-process file effect.

## Decision

1. The versioned `local-materialization/v1` contract has four bounded messages: session start,
   manifest declaration, chunk descriptor and finalize request. The canonical JSON Schemas live
   under `contracts/local-bridge/v1`. The Java Project API contract mirrors their structural
   invariants but does not persist or execute them in this unit.
2. A future Integration adapter authenticates both the tenant owner and the existing active
   Bridge credential outside the JSON payload. Project resolves the Bridge ID and copies the
   verified owner, device and opaque root-handle bindings into a Project-owned materialization
   session. The contract never carries a Bridge credential, local absolute path, root handle or
   client-supplied device identity.
3. A manifest declares only regular-file metadata: relative name, byte count and SHA-256 digest.
   Symlinks, special files and submodules are not representable as accepted entries. U04 owns
   path canonicalization, traversal/absolute-path rejection, filesystem scanning, size and
   file-count policy enforcement before any bytes become a Source snapshot.
4. A chunk message is metadata only. Its bytes travel in a future bounded streaming request body,
   never in JSON, telemetry, a Java DTO, or durable command evidence. Stable request IDs, file
   path, offset, declared length and body hash make replay/idempotency proof possible; U03 owns
   partial-byte isolation and persistence.
5. Finalize names only the session, stable request ID and full manifest digest. U05 publishes a new
   immutable server-managed Source snapshot only after every declared chunk and file hash is proven.
   It never overwrites or reuses an existing LOCAL SourceRepository and never creates a Directory,
   Task or Workspace.
6. Project owns session/snapshot/Source/Workspace authority. The client bridge owns the local
   read. Tooling/Sandbox performs isolated materialization through its existing OCI/Governance/
   Tool Ledger boundaries. Integration is transport/authentication only; Runtime only coordinates
   an already-created Project result and cannot invent a snapshot or session.
7. The published SourceRepository uses the distinct `MANAGED_SNAPSHOT` type and stores only opaque
   snapshot/object reference, manifest hash, content hash and source evidence. Normal Project Intake
   later creates a ProjectDirectory; Coding creates the Task/PlanStep/base/isolation-bound Workspace,
   whose content is materialized through OCI Sandbox from the managed snapshot.

## Failure and threat model

- Browser or client-supplied absolute paths, path-shaped handles and device claims do not grant
  filesystem access; the active Bridge is resolved server-side after credential verification.
- Traversal, drive prefixes, backslashes, symlink escape, submodule indirection, special files,
  manifest duplicates, decompression-style size abuse and hash mismatch are rejected before
  publication. Partial content remains isolated and is never `READY`.
- Cross-tenant, cross-owner, revoked/expired Bridge or session, stale/replayed request ID and
  mismatched session/device are rejected. Unknown byte-write/finalize effects remain blocked;
  no blind retry, force, rebase, remote push or overwrite is permitted.
- Chunk content, credentials, local paths and source contents are excluded from logs, telemetry,
  public views and durable command evidence. The Sandbox receives bytes only after Project has
  accepted the bounded session scope.

## Consequences

- Existing M19 Local Bridge registration and `SourceRepository.importLocal` remain compatible;
  no existing handle is treated as materialized source content.
- U02 adds Project-owned session persistence, expiry and owner/device binding. U03 adds isolated
  resumable bytes. U04 validates path/snapshot security. U05 performs verified atomic
  materialization into the existing Project/Sandbox flow. U06 owns cleanup, transport and gates.
- Product resolution for U05: publish Source only. No Task/Directory/Workspace binding is added to
  MaterializationSession; cleanup deletes snapshot bytes before Source metadata and blocks on failure.
- No migration, HTTP route, client UX, CLI/Desktop change, local filesystem operation, OCI call,
  Provider call, remote Git operation or production operation is introduced here.
