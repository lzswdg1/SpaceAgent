import { describe, expect, it, vi } from 'vitest'
import { loadOverview, loadAgentUsage, stopMonitoringSession, stopMonitoringSessions } from './overviewApi'
import type { AuthenticatedRequest } from './types'

describe('loadOverview', () => {
  it('loads overview, agent usage and realtime from authoritative monitoring routes', async () => {
    const request = vi.fn(async <T,>(path: string): Promise<T> => {
      if (path.includes('/overview')) return { overview: { totalAgents: 2 }, timeseries: [] } as T
      if (path.includes('/usage')) return { rows: [], totalCostUsd: null, truncated: false, groupBy: 'agent', range: '7d' } as T
      if (path.includes('/realtime')) return { runtimeActive: 0, processing: 0, perOrgActive: [], ts: '2026-08-24T00:00:00Z' } as T
      if (path.includes('/sessions')) return { sessions: [], total: 0, page: 1, pageSize: 25, runtimeActive: 0 } as T
      throw new Error(path)
    }) as AuthenticatedRequest & ReturnType<typeof vi.fn>

    const result = await loadOverview(request, '7d', 2)

    expect(result.overview.overview.totalAgents).toBe(2)
    expect(request).toHaveBeenCalledWith('/api/v1/monitoring/usage?range=7d&groupBy=agent')
    expect(request).toHaveBeenCalledWith('/api/v1/monitoring/sessions?range=7d&status=all&page=2&pageSize=10')
    expect(request).toHaveBeenCalledTimes(4)
  })

  it.each(['1d', '7d', 'all'] as const)('uses backend range %s without calculating a client period', async range => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await loadOverview(request, range)
    expect(request).toHaveBeenCalledWith(`/api/v1/monitoring/overview?range=${range}`)
    expect(request).toHaveBeenCalledWith(`/api/v1/monitoring/usage?range=${range}&groupBy=agent`)
  })

  it('loads lifetime Agent usage independently and preserves failures', async () => {
    const request = vi.fn(async () => ({ rows: [] })) as unknown as AuthenticatedRequest
    await loadAgentUsage(request)
    expect(request).toHaveBeenCalledWith('/api/v1/monitoring/usage/agents')
    const failed: AuthenticatedRequest = async () => { throw Error('unavailable') }
    await expect(loadAgentUsage(failed)).rejects.toThrow('unavailable')
  })

  it('maps owner-scoped single and batch stop commands', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await stopMonitoringSession(request, 'run/1')
    await stopMonitoringSessions(request, ['run/1', 'run/2'])
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/monitoring/sessions/run%2F1/stop', { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/monitoring/sessions/batch-stop', {
      method: 'POST', body: JSON.stringify({ sessionIds: ['run/1', 'run/2'] }),
    })
  })
})
