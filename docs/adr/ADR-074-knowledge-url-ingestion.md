# ADR-074: Knowledge URL ingestion authority

- Status: Accepted
- Date: 2026-09-09
- Milestone: M64-PR1

## Context

Knowledge documents can retain an external reference, and Tooling has a bounded one-shot HTTP fetch tool, but no
durable URL refresh lifecycle owns conditional requests, observations, immutable content versions or atomic Chunk
replacement. Treating the URL as a mutable Document field would lose fetch and deduplication evidence.

## Decision

- Knowledge owns a separate `KnowledgeUrlJob`, immutable fetch observations and immutable content versions. A Job
  binds tenant, owner, target KnowledgeDocument, canonical public HTTPS URL/origin and bounded refresh policy.
- URL normalization lowercases IDNA host, removes the default port, normalizes path and rejects credentials,
  fragments, IP literals, localhost, non-HTTPS and overlong input. U03 still performs DNS pinning and redirect-time
  SSRF validation; domain syntax is not treated as network proof.
- PostgreSQL clock/lease/fence will own refresh scheduling. Network fetch is outside transactions and each request
  uses an exact validated DNS answer, bounded redirects/body/time, ETag and Last-Modified.
- Observations retain URL/content hashes, status, validators, outcome and safe code, never response body or secret.
  UNKNOWN is not automatically retried because completion cannot be proven.
- Content bytes publish to an opaque managed object reference before a STAGED immutable ContentVersion is visible.
  ACTIVE requires MIME/charset/parser validation; changed content atomically switches the document's Chunk set,
  unchanged content never repeats embedding, and old versions become SUPERSEDED without mutation.
- U02 adds persistence; U03 secure fetch; U04 parse/index and atomic Chunk switch. U05 separately decides the
  non-Project Document Workspace owner; U06 persistence/Sandbox tools; U07 HTTP/refresh/cleanup/final gates.

## Consequences

U01 defined framework-independent domain/API. U02 adds V1066/78 and scoped memory/PostgreSQL repositories for
Jobs, lease/fence claims, hash-only Observations and immutable ContentVersions with atomic active switching.
U03 adds Tooling public secure fetch using per-hop DNS pinning, explicit redirect revalidation, bounded conditional
headers, MIME/charset/body/time limits and safe failures. U04 adds hash-addressed managed bytes, strict parser/
sanitizer, deduplicated embedding and transactional ContentVersion/Chunk activation. Document Workspace, HTTP
product APIs, frontend and public external operations remain.
