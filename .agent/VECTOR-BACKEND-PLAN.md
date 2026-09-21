# M80-PR1 — deployment-selectable Knowledge vector backends

Workflow: STRICT. Status: COMPLETE.

## Objective and scope

Retain Milvus unchanged and implement a PostgreSQL/pgvector VectorIndexGateway. A deployment
selects exactly one backend (`none`, `milvus`, `pgvector`), consistently in API and indexing workers.
Only Knowledge owns index persistence; permissions, sources, generations, activation and paid
Embedding accounting keep their existing Java/PostgreSQL owners. No frontend or live deployment.

## Durable compatibility boundary

V1098 adds compact pgvector/FTS replica tables and an atomic deployment backend selection record.
Pre-existing generation descriptors conservatively pin `milvus`; fresh deployments bind their first
selected backend. A conflicting API/Worker selection fails before requests or model calls. `none`
does not erase the binding. No automatic fallback, dual-write, destructive switch, or paid reindex.
Switching an existing deployment requires a separately reviewed migration/rebuild; this milestone
only implements the two deployment choices, not cross-backend migration.

## Implementation and verification

1. Add migration/selection guard and exact cosine + bounded Chinese/Latin lexical pgvector adapter.
   Use scope/base/generation predicates in SQL, immutable row upserts, source-text hashes and compact
   vector storage. Default exact scoped search avoids global ANN post-filter recall/isolation pitfalls
   and HNSW build memory on small servers. PostgreSQL lexical ranking is not Milvus BM25 parity.
2. Integrate early geometry validation, selected-only Spring wiring, worker/Compose examples and
   operating instructions. Deterministic real PostgreSQL tests cover fresh/upgrade, mismatch, batch
   conflict, scope/generation/schema/model separation, lexical recall, activation/restore/delete.
   Preserve Milvus adapter/real local dependency tests. Full Maven/package/architecture gates once
   after stabilizing; no paid model/GitHub/production tests or existing-stack rebuild.

## Rollback and acceptance limits

New tables do not rewrite sources or active pointers. Old binaries remain usable for Milvus-only
deployments; never roll a pgvector-bound database back to a Milvus-only binary. Back up PostgreSQL,
original/vector checkpoint objects and encryption keys together. SQL replicas can be restored from
existing immutable vector checkpoints without buying new embeddings. Model dimensions above 16000
are unsupported by this pgvector adapter and rejected before model dispatch.

Evidence: full Maven local-dependency run, then the one temporary Artifact connection-initialization
failure retried unchanged (5/5 PASS). Fresh XML union: Shared25 + Platform860 + Admin17 = 902 PASS,
zero assertions/errors/skips after retry. Actual PG vectors/FTS, Milvus and Tika all exercised;
package/architecture/two-backend Compose/preflight/diff PASS. Full log:
ephemeral full-suite log; retry evidence was also ephemeral and is not distributed with the repository.
No runtime activated, images rebuilt, paid calls or cross-backend migration. 2CPU/4GB capacity and
semantic quality remain unclaimed. Next source action NONE; operating details in Knowledge RAG runbook.
