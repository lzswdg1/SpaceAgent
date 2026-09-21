# ADR-075: Knowledge-owned non-Project Document Workspace

- Status: Accepted
- Date: 2026-09-09
- Milestone: M64-PR1

## Context

Chat needs durable document file operations without fabricating a Project, SourceRepository, ProjectDirectory or
Git Workspace. Reusing Project Workspace would incorrectly require source/Git semantics; putting document state in
Runtime or Tooling would create a second content authority.

## Decision

- Knowledge owns `DocumentWorkspace` metadata, opaque object namespace, quota counters and lifecycle. It is a
  separate aggregate from Project Workspace and has no Project, Directory, Source, Task, PlanStep or Git binding.
- Scope is exactly USER or ORGANIZATION. USER scope ID equals the owner user; ORGANIZATION scope ID equals tenant.
  Identity public APIs authorize active membership and manager-only Organization mutations in later Units.
- PostgreSQL will own quota reservation/release, revision CAS and cleanup metadata. Object storage references are
  opaque `document-workspace:*` identifiers; no client/server absolute path, mount path or credential is durable.
- Tooling/Sandbox owns bounded file transport only. Every read/write/delete/list operation runs in isolated OCI,
  passes Governance and Tool Ledger, and returns evidence to Java. No host-process fallback and no Git capability.
- Runtime coordinates Tool approval/UNKNOWN/Continuation but cannot create Workspace state or select quota usage.
  Ambiguous writes retain reservation and remain UNKNOWN until a read-only postcondition proves the effect.
- Cleanup is bytes-first then metadata. Failure remains BLOCKED and cannot claim completion.
- U06 adds persistence and Sandbox tools. U07 adds owner HTTP, URL refresh/cleanup integration, documentation and
  final gates.

## Consequences

This Unit defines only framework-independent Knowledge domain/public API. Implementation is committed under batch
M64-PR1-B01 and remains pending combined proof. It adds no migration, repository, Sandbox call, Runtime wiring,
HTTP endpoint, frontend or external operation.

U06 realizes the persistence and execution boundary without changing ownership: V1067 stores Workspace, file
hash/size and mutation evidence under Knowledge; Integration invokes Governance and Tooling public APIs; Tooling's
durable ledger wraps dedicated OCI commands under `document-workspaces/{uuid}`. The host provisions only an empty
server-managed mount namespace. It never reads or writes document bytes. PENDING/UNKNOWN mutations retain their
quota reservation and unique active-path fence until read-only reconciliation in U07 proves a terminal result.

U07 exposes the owner APIs through authenticated HTTP and four dedicated Runtime Tool definitions. Runtime does
not create a Project or add a second Tool claim: the Document Workspace coordinator owns the exact Sandbox claim.
V1068 binds operation evidence to the originating Run/RunStep. Reconciliation executes only hash/size/existence
proof in OCI and resolves both Knowledge and the original Tool Ledger by revision CAS; mismatch remains UNKNOWN.
User/Organization cleanup blocks on PENDING/UNKNOWN, clears bytes through a ledgered OCI command and only then
deletes metadata. The host may remove the resulting empty namespace but never reads or mutates document bytes.
