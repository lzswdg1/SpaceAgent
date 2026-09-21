# ADR-076: Artifact-owned managed object lifecycle

- Status: Accepted
- Date: 2026-09-09
- Milestone: M64-PR2

## Context

The existing `Artifact` is immutable Project/Task/Run/Workspace evidence. Large Artifact and Knowledge document
bytes need a shared storage capability without giving Project, Knowledge, Runtime, a browser or a storage worker
authority over object lifecycle, credentials or physical coordinates.

## Decision

- Artifact owns `ManagedArtifactObject`, staging session, exact owner reference, legal hold, retention and fenced
  deletion job state in Java/PostgreSQL. Existing Project evidence `Artifact` remains unchanged.
- An object is tenant-scoped and content-addressed by SHA-256, exact byte size and bounded media type. Durable
  storage and encryption references are opaque `artifact-object:*` and `tenant-key:*` values; no bucket, endpoint,
  filesystem path, signed URL or credential is business state.
- Upload bytes enter an expiring `OPEN` staging session. Only exact size/hash verification advances to `VERIFIED`;
  only a verified session may atomically publish one immutable `READY` object. Partial, mismatch, expiry, crash or
  UNKNOWN never exposes READY metadata and never overwrites an existing object.
- Artifact owns references from ARTIFACT, PROJECT and KNOWLEDGE resources. Owner modules persist only the opaque
  object ID and use the Artifact public Application API to attach/release exact references.
- Retention and active legal holds prevent deletion. Deletion requires zero active references and elapsed
  retention, then follows a PostgreSQL leased/fenced bytes-first job. Unknown or failed byte deletion is BLOCKED;
  metadata cannot claim DELETED until the adapter proves byte removal.
- A future download API may issue a short-lived exact-object capability only after tenant/owner authorization.
  It never returns storage credentials and cannot list or mutate the backing store.
- Local filesystem and S3-compatible adapters implement one object gateway contract. They own transport bytes,
  not metadata, reference, hold, retention or deletion decisions. No Provider, MCP, Git or Runtime state is added.

## Consequences

U01 defines framework-independent domain/public contracts and tests only. U02 adds forward-only persistence,
U03/U04 add local and official-SDK S3-compatible adapters, U05 adds retention/deletion recovery, and U06 connects
Project/Knowledge plus final documentation and gates. M64-PR2-B01 holds validation for U01-U05 by default.

U06 implements the public lifecycle without moving authority: Artifact persists OPEN before provisioning an opaque
staging ref, then requires UPLOADING bytes and exact VERIFIED evidence before atomic PUBLISHED/READY metadata.
Integration validates Project/Knowledge/Artifact owner IDs only through public owner APIs. HTTP accepts bounded
base64 chunks and opaque IDs; local and S3 configuration is operator-only. Organization cleanup rejects active
legal holds or claimed deletion jobs, deletes staging/object bytes first, and only then removes Artifact metadata.
