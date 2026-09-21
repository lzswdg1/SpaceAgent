export type TraceSummary = {
  id: string
  organizationId: string
  sessionId: string
  sessionName: string | null
  agentId: string
  agentName: string | null
  userId: string
  rootSpanId: string
  status: string
  isError: boolean
  errorMessage: string | null
  startTime: string
  endTime: string | null
  durationMs: number | null
  firstTokenMs: number | null
  llmMs: number
  toolWallMs: number
  toolDurationSumMs: number
  spanCount: number
  llmTurns: number
  toolCalls: number
  inputTokens: number
  outputTokens: number
  totalTokens: number
  cacheCreateTokens: number
  cacheReadTokens: number
  costUsd: number | null
  costEstimated: boolean
  metadata: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export type TraceSpan = {
  id: string
  traceId: string
  parentSpanId: string | null
  spanType: string
  name: string
  status: string
  isError: boolean
  errorMessage: string | null
  startTime: string
  endTime: string | null
  durationMs: number | null
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  cacheCreateTokens: number | null
  cacheReadTokens: number | null
  inputPreview: string | null
  outputPreview: string | null
  metadata: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export type TraceList = { traces: TraceSummary[]; total: number; limit: number; offset: number }
export type TraceDetail = { trace: TraceSummary; spans: TraceSpan[] }
export type TraceStats = {
  totalTraces: number
  successTraces: number
  errorTraces: number
  abortedTraces: number
  avgDurationMs: number
  p50DurationMs: number
  p95DurationMs: number
  avgFirstTokenMs: number
  totalTokens: number
  totalCostUsd: number | null
  range: string
}

export type ConversationTraceGroup = {
  sessionId: string
  sessionName: string
  mode: 'chat' | 'project'
  projectId: string | null
  traces: TraceSummary[]
  totalTokens: number
  latestAt: string
}
