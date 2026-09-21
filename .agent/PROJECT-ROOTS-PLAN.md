# Project roots and storage retention

Status: COMPLETE
Workflow: STRICT (directory lifecycle, storage initialization, deletion semantics)
User authority: repository or named blank physical-backed roots; tenant deletion retains code;
physical repository deletion is administrator-only.

1. Backend: forward migration and empty-root storage; archive defaults; remove physical cleanup from tenant workspace archive.
2. Frontend: flat roots and direct Conversations; empty/repository root creation and confirmed logical deletion.
3. Verification: focused lifecycle/HTTP/PostgreSQL/UI checks; local activation preserves existing code and conversations.

Implemented: V1083 root/source backfill, startup empty-root reconciliation, public Root CRUD/prepare,
flat root navigation, root-scoped naming/deletion, and no cleanup on tenant Workspace archival.
Organization cleanup now blocks retained-code deletion without a successful internal SystemAdministrator
ORGANIZATION_DELETE command. An administrator deletion request resumes only this specific storage gate,
not unrelated blocked/UNKNOWN cleanup failures.

Verification (2026-09-12): full Maven test PASS; Web 84/84 and production build PASS; isolated Chrome
peer-root and action-target test PASS; architecture, diff, shell syntax and development/release Compose
configuration PASS. Root HTTP tests prove real empty Git storage, cross-tenant denial, retained Workspace
files after both Workspace/root deletion, and refusal of new deleted-root Chat requests. PostgreSQL tests
prove V1082 upgrade preserves Conversation directory IDs and administrator-only cleanup release.

Local activation: only platform-server and web images/containers updated; existing PostgreSQL/sandbox
reused. Backend image Git/non-root/offline clone gate PASS. Upgrade from V1082 to V1083 retained all 8
Conversations and 4 directories; two legacy empty roots received separate READY sources/physical mirrors
(source count 1 -> 3). Original repository retained. Backend readiness UP, Project page HTTP 200.
Pre-upgrade backup was restored and checked in an isolated disposable database:
`/tmp/spaceagent-roots-upgrade-EwUQ1P/spaceagent-platform-20260912T135635Z.dump`.

No remote push, paid model call, live GitHub operation, or existing physical code deletion is part of this work.
