# ADR-005: Tenant-scoped Project Foundation

- Status: Accepted
- Date: 2026-08-22
- Scope: M11-PR1

## Context

Project previously existed only as a framework-independent reference type plus a
fail-closed `ProjectOwnershipPort`. There was no authoritative Project row, explicit
Project membership, Application API, HTTP API, or durable authorization source. As a
result, PROJECT Memory could not establish access from canonical Project state.

## Decision

### Project is an aggregate

Project is the tenant-scoped aggregate root for Project identity, ownership,
description, and lifecycle. It is persisted in `platform_projects` and may transition
from `ACTIVE` to `ARCHIVED`. Rename and description changes are aggregate behaviors;
there are no public state setters, and archived Projects reject further modification.
Archive is logical and never physically deletes the row.

Project owns only identity, ownership, membership, and authorization in this batch. It
does not own Git/source repositories, Workspace, Task, Agent execution, Memory storage,
or Artifact state.

### Tenant authorization and Project authorization are separate

Tenant membership answers whether a user may operate in a Tenant at all. Project
membership answers which Projects in that Tenant the user may see or change. A Tenant
role does not implicitly grant access to every Project, and a Project role cannot make a
user valid in a Tenant whose membership is inactive.

The effective Project permissions are:

| Project role | View | Modify | Archive | Manage members | PROJECT Memory |
| --- | --- | --- | --- | --- | --- |
| OWNER | yes | yes | yes | yes | yes |
| ADMIN | yes | yes | no | no | yes |
| MEMBER | yes | no | no | no | yes |
| VIEWER | yes | no | no | no | no |

Every Application API command/query carries both `tenantId` and `userId`. A Project is
resolved inside the requested Tenant before membership is evaluated, so a valid ID from
another Tenant is not a cross-Tenant lookup capability.

### ProjectMembership is explicit

`platform_project_memberships` stores one role per `(project_id, user_id)`. Project
creation inserts the owner's `OWNER` membership in the same transaction. The owner
cannot be removed or downgraded, and only the owner may add, change, or remove other
members. Adding an existing member changes the role through
`ProjectMembership.changeRole` while preserving membership identity and creation time.

We do not reuse Tenant Membership because its scope and lifecycle are different. A user
may be an active Tenant member without access to a particular Project; conversely,
Project access must be revocable without changing Tenant-wide permissions. Reusing the
Tenant role would collapse these boundaries and make least-privilege Project sharing
impossible.

### Persistence and authorization adapters

The domain declares `ProjectRepository` and `ProjectMembershipRepository` persistence
ports. PostgreSQL implementations live in Project infrastructure; Application code never
uses JDBC. `PostgresProjectOwnershipAdapter` replaces the default fail-closed adapter for
OWNER, ADMIN, and MEMBER Project-memory authorization. Task ancestry remains fail-closed
because Task persistence is outside M11-PR1.

Production Flyway loads `db/platform-server` and `db/platform-runtime` together. Since
`V1006` already exists, the next legal migration is
`V1007__project_foundation.sql`, not the earlier estimated `V13`.
The migration is intentionally inert only for existing migration-isolation fixtures
that baseline an Agent-only partial schema without Identity tables; every runnable
platform path has the Identity baseline and creates the Project tables and foreign keys.

New Project and ProjectMembership identities use PostgreSQL `UUID`. Existing
`platform_tenants.id` and `platform_users.id` are authoritative `VARCHAR(36)` keys, so
`tenant_id`, `owner_id`, and `user_id` retain that physical type to preserve real foreign
keys without an out-of-scope Identity-wide key migration. Java exposes IDs as strings,
consistent with existing public contracts.

## Why SourceRepository, Workspace, and Task are excluded

The word “Repository” in the excluded product scope means Git/source Repository. The
persistence ports named `ProjectRepository` and `ProjectMembershipRepository` are part
of this foundation and do not model source code.

Git Repository, Workspace, and Task introduce independent lifecycle, provisioning, and
execution concerns. Adding them now would turn a bounded authorization foundation into a
premature Coding Platform design. They require separate objectives and migrations.

## Consequences

- Project rows and memberships are authoritative PostgreSQL state.
- Project CRUD and membership operations are available through a public Application API
  and `/api/v1/projects` HTTP adapters.
- Project names are unique within a Tenant, including archived Projects.
- PROJECT Memory has a real authorization source for OWNER/ADMIN/MEMBER; VIEWER is
  intentionally read-only at the Project boundary.
- TASK Memory remains fail-closed until a canonical Task-to-Project relation exists.
- No Legacy, frontend, Conversation, Memory schema, Runtime, Git, Workspace, Task,
  Artifact, Coding Agent, or Multi-Agent behavior is added by this decision.
