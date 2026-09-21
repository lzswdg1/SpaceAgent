# Logical Project roots and retained storage

Product roots are flat. Each root is either a repository root or a named empty managed root,
with Conversations directly beneath it. Project remains a hidden ownership/security scope, not an
additional folder in the UI. Existing default directory IDs and Conversations are preserved.

Default directories acquire a Project-owned GENERIC managed source with no remote URL. Java creates
an empty local Git mirror for such sources; Workspaces use the existing isolated clone pipeline.
The source's readiness is authoritative. Creation/initialization is an explicit command, and legacy
pending empty roots are initialized by a Project-owned startup reconciliation path. Clients never
supply host paths. Storage locations are derived from immutable IDs under the configured root.

Tenant root/source/workspace deletion means logical archival and retains physical code. Archival
must not call cleanup gateways. Root creation may reuse retained sources without reviving archived
directory IDs. Default directories can be archived and must not silently reappear on conversation
creation. Physical repository destruction remains a separate administrator cleanup operation and
is not exposed by tenant root deletion. Temporary failed provisioning/intake scratch cleanup is
distinct from deleting retained, successfully prepared repository storage.

Organization cleanup checks for retained code before any destructive step. Such cleanup requires
a successful SystemAdministrator `ORGANIZATION_DELETE` command in the internal command ledger.
Ordinary last-member leave does not satisfy this gate; the job becomes BLOCKED with
`PROJECT_STORAGE_ADMIN_REQUIRED`, retaining code and metadata for administrator review.

The change requires a forward migration for default-root source bindings and compatible memory/
PostgreSQL handling. Tests cover empty physical initialization, archival with retained files,
cross-tenant denial, flat UI roots, and source/root/conversation scope retention.
