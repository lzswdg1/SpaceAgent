import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { groupConversationTraces, loadTraceDetail, loadTracing } from './tracingApi'
import type { TraceSummary } from './types'

const trace = (overrides: Partial<TraceSummary>): TraceSummary => ({
  id: 'trace-1', organizationId: 'org', sessionId: 'chat-1', sessionName: 'Release', agentId: 'agent', agentName: 'Guardian', userId: 'user', rootSpanId: 'root', status: 'SUCCESS', isError: false, errorMessage: null, startTime: '2026-08-24T10:00:00Z', endTime: null, durationMs: 100, firstTokenMs: null, llmMs: 80, toolWallMs: 0, toolDurationSumMs: 0, spanCount: 1, llmTurns: 1, toolCalls: 0, inputTokens: 10, outputTokens: 20, totalTokens: 30, cacheCreateTokens: 0, cacheReadTokens: 0, costUsd: null, costEstimated: false, metadata: { source: 'chat' }, createdAt: '2026-08-24T10:00:00Z', updatedAt: '2026-08-24T10:00:00Z', ...overrides,
})

describe('tracing API', () => {
  it('groups AgentRun traces under Chat and Project conversations', () => {
    const groups = groupConversationTraces([
      trace({ id: 'run-1' }),
      trace({ id: 'run-2', totalTokens: 40, startTime: '2026-08-24T11:00:00Z' }),
      trace({ id: 'run-3', sessionId: 'project-chat', sessionName: 'Project review', metadata: { source: 'project', projectId: 'project-1' } }),
    ])
    expect(groups).toHaveLength(2)
    expect(groups.find(({ sessionId }) => sessionId === 'chat-1')).toMatchObject({ mode: 'chat', totalTokens: 70 })
    expect(groups.find(({ sessionId }) => sessionId === 'project-chat')).toMatchObject({ mode: 'project', projectId: 'project-1' })
  })

  it('uses only user-scoped tracing routes', async () => {
    const request = vi.fn(async (path: string) => path.includes('/stats') ? {} : { traces: [] }) as unknown as AuthenticatedRequest
    await loadTracing(request)
    await loadTraceDetail(request, 'trace/1')
    expect(request).toHaveBeenCalledWith('/api/v1/users/tracing/traces?limit=100&offset=0')
    expect(request).toHaveBeenCalledWith('/api/v1/users/tracing/stats?range=7d')
    expect(request).toHaveBeenCalledWith('/api/v1/users/tracing/traces/trace%2F1')
  })
})
