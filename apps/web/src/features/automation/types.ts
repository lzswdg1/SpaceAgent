export type AutomationAgent = {
  id: string
  name: string
  status: string
}

export type AutomationScheduleType = 'one_time' | 'periodic'
export type AutomationScheduleStatus = 'active' | 'paused' | 'completed' | 'failed' | 'archived'

export type AutomationSchedule = {
  id: string
  agentId: string
  description: string
  prompt: string
  type: AutomationScheduleType
  cronExpr: string | null
  scheduledAt: string | null
  timezone: string
  status: AutomationScheduleStatus
  nextRunAt: string | null
  lastRunAt: string | null
  lastRunStatus: string | null
  lastError: string | null
  runCount: number
  maxRetries: number
  bullJobId: string | null
  revision: number
  createdAt: string
  updatedAt: string
}

export type AutomationExecution = {
  id: string
  taskId: string
  sessionId: string | null
  agentRunId: string | null
  status: 'queued' | 'waiting_approval' | 'running' | 'success' | 'failed' | 'unknown' | 'rejected' | 'expired' | 'cancelled'
  approvalId: string | null
  scheduledFor: string
  startedAt: string | null
  completedAt: string | null
  error: string | null
  tokenUsage: { inputTokens: number; outputTokens: number }
  createdAt: string
}

export type CreateAutomationScheduleInput = {
  description: string
  prompt: string
  type: AutomationScheduleType
  cronExpr: string | null
  scheduledAt: string | null
  timezone: string
  maxRetries: number
}

export type UpdateAutomationScheduleInput = {
  description: string
  prompt: string
  cronExpr: string | null
  scheduledAt: string | null
  timezone: string
  maxRetries: number
  expectedRevision: number
}
