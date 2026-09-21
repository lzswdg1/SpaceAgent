import type { AuthenticatedRequest } from '../overview/types'
import type { AutomationAgent, AutomationExecution, AutomationSchedule, CreateAutomationScheduleInput, UpdateAutomationScheduleInput } from './types'

const base = (agentId: string) => `/api/v1/agents/${encodeURIComponent(agentId)}/scheduled-tasks`

export const listAutomationAgents = (request: AuthenticatedRequest) => request<AutomationAgent[]>('/api/v1/agents')

export const listAutomationSchedules = (request: AuthenticatedRequest, agentId: string) =>
  request<AutomationSchedule[]>(base(agentId))

export const getAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
) => request<AutomationSchedule>(`${base(agentId)}/${encodeURIComponent(scheduleId)}`)

export const createAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  input: CreateAutomationScheduleInput,
) => request<AutomationSchedule>(base(agentId), { method: 'POST', body: JSON.stringify(input) })

export const updateAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
  input: UpdateAutomationScheduleInput,
) => request<AutomationSchedule>(`${base(agentId)}/${encodeURIComponent(scheduleId)}`, {
  method: 'PATCH', body: JSON.stringify(input),
})

export const pauseAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
) => request<AutomationSchedule>(`${base(agentId)}/${encodeURIComponent(scheduleId)}/pause`, { method: 'POST' })

export const resumeAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
) => request<AutomationSchedule>(`${base(agentId)}/${encodeURIComponent(scheduleId)}/resume`, { method: 'POST' })

export const archiveAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
) => request<void>(`${base(agentId)}/${encodeURIComponent(scheduleId)}`, { method: 'DELETE' })

export const triggerAutomationSchedule = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
  idempotencyKey: string,
) => request<AutomationExecution>(`${base(agentId)}/${encodeURIComponent(scheduleId)}/trigger`, {
  method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify({}),
})

export const listAutomationExecutions = (
  request: AuthenticatedRequest,
  agentId: string,
  scheduleId: string,
  limit = 20,
) => request<AutomationExecution[]>(`${base(agentId)}/${encodeURIComponent(scheduleId)}/executions?limit=${limit}`)
