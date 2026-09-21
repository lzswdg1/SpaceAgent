# ADR-088: Artifact publication identity and deletion interlock

Status: Accepted. Date: 2026-09-15. Supersedes cross-staging content identity reuse in ADR-076.

Verified bytes do not grant another uploader's access or retention. Each staging publication
has a separate immutable storage key derived from tenant, staging reference, hash, size and
encryption reference. A retry of the same staging resolves the same bytes and published identity;
another staging does not reuse READY/DELETE_PENDING/BLOCKED/DELETED identity or its bytes.
This deliberately favors reliable private publication/retention over cross-user deduplication.
Quotas count each retained publication; there is no fictitious dedup saving.

V1096 drops only the content-identity uniqueness constraint. All existing records and storage keys
remain readable and deletable; globally unique storage references remain enforced. No destructive
backfill or object movement. Rollback requires retaining the new schema and rolling compatible
application code forward; blindly restoring the old content uniqueness is not valid after new publications.

READY is the only state that admits a new active reference or legal hold. Reference/hold/delete
admission locks the Artifact object row and rechecks eligibility in the same owner transaction.
Once DELETE_PENDING wins, new holds/references explicitly conflict instead of promising retention
that cannot be honored. The Worker locks the object and checks legacy active holds/references
before granting deletion execution. Ineligible jobs become BLOCKED without deleting bytes.
Unknown deletion remains BLOCKED and requires explicit owner-controlled retry/reconciliation;
no automatic replay, resurrection or new publication may share that old storage key.

Physical deletion still requires its existing administrator/owner authorization and fenced ledger.
This protocol changes no Project/Knowledge/Runtime authority, and ordinary logical root deletion
continues retaining physical code. Tests cover current holds, pending publication, repeated upload,
cross-user equal-content access, concurrent PG reference/hold/delete and old-to-V1096 migration.
