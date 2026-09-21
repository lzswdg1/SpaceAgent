import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { consolidateTaskMemory, listMemoryCandidates, proposeMemoryCandidate, recallMemory, reviewMemoryCandidate } from './memoryApi'

describe('memory API', () => {
  it('loads bounded consolidated and pending Memory by exact scope', async () => {
    const request = vi.fn(async () => ([])) as unknown as AuthenticatedRequest
    const scope = { scope: 'PROJECT' as const, scopeId: 'project/1' }
    await recallMemory(request, scope, 25)
    await listMemoryCandidates(request, scope, 25)
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/memory?scope=PROJECT&scopeId=project%2F1&limit=25')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/memory/candidates?scope=PROJECT&scopeId=project%2F1&limit=25')
  })

  it('proposes and reviews a bounded candidate', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = { scope: { scope: 'USER' as const, scopeId: 'user/1' }, kind: 'PREFERENCE' as const,
      sourceId: 'manual/1', sourceType: 'MANUAL_WEB', content: 'Prefer concise status updates',
      confidence: 0.9, dedupeKey: null }
    await proposeMemoryCandidate(request, input)
    await reviewMemoryCandidate(request, 'candidate/1', { decision: 'ACCEPTED', reason: 'Confirmed' })
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/memory/candidates', {
      method: 'POST', body: JSON.stringify(input),
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/memory/candidates/candidate%2F1/review', {
      method: 'POST', body: JSON.stringify({ decision: 'ACCEPTED', reason: 'Confirmed' }),
    })
  })

  it('submits backend-owned consolidation thresholds and promotion scopes', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const input = { taskId: 'task/1', projectId: 'project/1', userId: 'user/1',
      acceptThreshold: 0.55, promotionThreshold: 0.75 }
    await consolidateTaskMemory(request, input)
    expect(request).toHaveBeenCalledWith('/api/v1/memory/consolidate', {
      method: 'POST', body: JSON.stringify(input),
    })
  })
})
