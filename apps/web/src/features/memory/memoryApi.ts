import type { AuthenticatedRequest } from '../overview/types'
import type { MemoryCandidate, MemoryConsolidation, MemoryKind, MemoryScopeRef, ScopedMemory } from './types'

const scopeQuery = (scope: MemoryScopeRef, limit: number) => {
  const query = new URLSearchParams({ scope: scope.scope, scopeId: scope.scopeId, limit: String(limit) })
  return query.toString()
}

export const recallMemory = (
  request: AuthenticatedRequest,
  scope: MemoryScopeRef,
  limit = 50,
) => request<ScopedMemory[]>(`/api/v1/memory?${scopeQuery(scope, limit)}`)

export const listMemoryCandidates = (
  request: AuthenticatedRequest,
  scope: MemoryScopeRef,
  limit = 50,
) => request<MemoryCandidate[]>(`/api/v1/memory/candidates?${scopeQuery(scope, limit)}`)

export const proposeMemoryCandidate = (
  request: AuthenticatedRequest,
  input: {
    scope: MemoryScopeRef
    kind: MemoryKind
    sourceId: string
    sourceType: string
    content: string
    confidence: number
    dedupeKey: string | null
  },
) => request<MemoryCandidate>('/api/v1/memory/candidates', {
  method: 'POST', body: JSON.stringify(input),
})

export const reviewMemoryCandidate = (
  request: AuthenticatedRequest,
  candidateId: string,
  input: { decision: 'ACCEPTED' | 'REJECTED'; reason: string | null },
) => request<MemoryCandidate>(`/api/v1/memory/candidates/${encodeURIComponent(candidateId)}/review`, {
  method: 'POST', body: JSON.stringify(input),
})

export const consolidateTaskMemory = (
  request: AuthenticatedRequest,
  input: {
    taskId: string
    projectId: string | null
    userId: string | null
    acceptThreshold: number
    promotionThreshold: number
  },
) => request<MemoryConsolidation>('/api/v1/memory/consolidate', {
  method: 'POST', body: JSON.stringify(input),
})
