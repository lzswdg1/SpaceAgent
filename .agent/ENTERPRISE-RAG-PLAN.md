# Enterprise RAG Public Status

> Status: COMPLETE_WITH_EXTERNAL_ACCEPTANCE_PENDING

## Implemented

- Personal/Organization Knowledge authorization and collection grants.
- Durable indexing jobs, bounded embedding batches, publication generations and deletion recovery.
- Isolated Tika parsing, multiple chunking strategies and preview without model calls.
- Deployment-selected Milvus dense/BM25 or PostgreSQL pgvector/vector+text retrieval.
- Optional reranking, citation/context budgeting, independent Knowledge Worker and operational
  repair paths.

## Authority and failure boundaries

- PostgreSQL owns document, permission, job, generation and publication lifecycle state.
- Vector backends are rebuildable indexes, never authorization or recovery authority.
- Inference owns Provider credentials and model-call accounting; parser/index workers receive no
  Provider secret beyond the exact bounded call path.
- Lease loss, partial publication and ambiguous deletion fail closed and require reconciliation.

## Not certified

- Production corpus quality, HA/capacity sizing, paid Provider behavior and external object/vector
  service operations.
- Implicit migration between vector backends.

See `docs/design/enterprise-rag/`, `docs/operations/KNOWLEDGE-RAG-RUNBOOK.md` and
`docs/adr/ADR-086-enterprise-rag-milvus.md` for the supported public design.
