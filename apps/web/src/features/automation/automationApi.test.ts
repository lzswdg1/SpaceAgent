import { describe, expect, it, vi } from 'vitest'
import type { AuthenticatedRequest } from '../overview/types'
import { archiveAutomationSchedule, createAutomationSchedule, getAutomationSchedule, listAutomationAgents, listAutomationExecutions, listAutomationSchedules, pauseAutomationSchedule, resumeAutomationSchedule, triggerAutomationSchedule, updateAutomationSchedule } from './automationApi'

describe('automation API', () => {
  it('loads agents, schedules, details and executions through encoded public routes', async () => {
    const request = vi.fn(async () => ([])) as unknown as AuthenticatedRequest
    await listAutomationAgents(request)
    await listAutomationSchedules(request, 'agent/1')
    await getAutomationSchedule(request, 'agent/1', 'schedule/1')
    await listAutomationExecutions(request, 'agent/1', 'schedule/1', 25)
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/agents')
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/agents/agent%2F1/scheduled-tasks')
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/agents/agent%2F1/scheduled-tasks/schedule%2F1')
    expect(request).toHaveBeenNthCalledWith(4, '/api/v1/agents/agent%2F1/scheduled-tasks/schedule%2F1/executions?limit=25')
  })

  it('creates and revision-fences schedule edits', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    const create = { description: 'Daily review', prompt: 'Review status', type: 'periodic' as const,
      cronExpr: '0 0 9 * * *', scheduledAt: null, timezone: 'UTC', maxRetries: 2 }
    await createAutomationSchedule(request, 'agent/1', create)
    await updateAutomationSchedule(request, 'agent/1', 'schedule/1', {
      description: 'Morning review', prompt: 'Review status', cronExpr: '0 0 8 * * *',
      scheduledAt: null, timezone: 'Asia/Shanghai', maxRetries: 1, expectedRevision: 4,
    })
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/agents/agent%2F1/scheduled-tasks', {
      method: 'POST', body: JSON.stringify(create),
    })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/agents/agent%2F1/scheduled-tasks/schedule%2F1', {
      method: 'PATCH', body: JSON.stringify({ description: 'Morning review', prompt: 'Review status',
        cronExpr: '0 0 8 * * *', scheduledAt: null, timezone: 'Asia/Shanghai', maxRetries: 1,
        expectedRevision: 4 }),
    })
  })

  it('maps lifecycle actions and a single idempotent manual trigger', async () => {
    const request = vi.fn(async () => ({})) as unknown as AuthenticatedRequest
    await pauseAutomationSchedule(request, 'agent/1', 'schedule/1')
    await resumeAutomationSchedule(request, 'agent/1', 'schedule/1')
    await triggerAutomationSchedule(request, 'agent/1', 'schedule/1', 'manual-trigger-1')
    await archiveAutomationSchedule(request, 'agent/1', 'schedule/1')
    const base = '/api/v1/agents/agent%2F1/scheduled-tasks/schedule%2F1'
    expect(request).toHaveBeenNthCalledWith(1, `${base}/pause`, { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(2, `${base}/resume`, { method: 'POST' })
    expect(request).toHaveBeenNthCalledWith(3, `${base}/trigger`, {
      method: 'POST', headers: { 'Idempotency-Key': 'manual-trigger-1' }, body: JSON.stringify({}),
    })
    expect(request).toHaveBeenNthCalledWith(4, base, { method: 'DELETE' })
  })
})
