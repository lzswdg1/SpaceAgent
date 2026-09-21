#!/usr/bin/env bash
set -euo pipefail

# Read-only preflight: never changes Docker Desktop allocation or an existing stack.
read -r rag_memory rag_cpus rag_arch < <(docker info --format '{{.MemTotal}} {{.NCPU}} {{.Architecture}}')
rag_backend="${PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE:-milvus}"
if [[ "$rag_backend" == pgvector ]]; then
  if [[ ! "$rag_memory" =~ ^[0-9]+$ || "$rag_memory" -le 0 || ! "$rag_cpus" =~ ^[0-9]+$ || "$rag_cpus" -lt 2 ]]; then
    echo "pgvector preflight unavailable: readable Docker resources and at least 2 CPUs required." >&2
    exit 1
  fi
  echo "pgvector selected: existing PostgreSQL extension, no Milvus RAM/image/credential requirement. Capacity and extension availability still need validation."
  exit 0
fi
if [[ "$rag_backend" != milvus ]]; then
  echo "Choose milvus or pgvector for the Knowledge dependency preflight." >&2
  exit 1
fi
# Docker reports usable guest memory, slightly less than its configured allocation.
if [[ ! "$rag_memory" =~ ^[0-9]+$ || "$rag_memory" -lt 8053063680 ]]; then
  echo "RAG environment unavailable: configure at least 8 GiB Docker RAM (7.5 GiB usable); observed ${rag_memory:-unknown} bytes." >&2
  exit 1
fi
if [[ ! "$rag_cpus" =~ ^[0-9]+$ || "$rag_cpus" -lt 2 ]]; then
  echo "RAG environment unavailable: Docker requires at least 2 CPUs." >&2
  exit 1
fi
if [[ ! "${MILVUS_IMAGE:-}" =~ ^milvusdb/milvus:v2\.6\.22@sha256:[a-f0-9]{64}$ ]]; then
  echo "Set MILVUS_IMAGE to the verified milvusdb/milvus:v2.6.22@sha256 digest for this environment." >&2
  exit 1
fi
rag_bootstrap="${MILVUS_BOOTSTRAP_PASSWORD:-}"
if [[ ${#rag_bootstrap} -lt 16 || ${#rag_bootstrap} -gt 72 ]]; then
  echo "Set a 16-72 character non-default MILVUS_BOOTSTRAP_PASSWORD." >&2
  exit 1
fi
echo "RAG preflight passed for ${rag_arch}: resources and required configuration present; server compatibility still needs integration proof."
