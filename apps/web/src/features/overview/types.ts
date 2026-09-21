export type UsageRange = '1d' | '7d' | 'all'

export type MonitoringOverview = {
  totalOrganizations: number
  totalAgents: number
  totalSessions: number
  activeSessions: number
  idleSessions: number
  closedSessions: number
  runtimeActiveSessions: number
  totalInputTokens: number
  totalOutputTokens: number
  totalCacheReadTokens: number
  totalCacheCreateTokens: number
  totalCostUsd: number | null
  peakConcurrent: number
  hasIncompleteCost: boolean
  range: string
  bucketSize: string
}

export type UsageTimeseriesPoint = {
  bucket: string
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreateTokens: number
  costUsd: number | null
  calls: number
  activeSessions: number
}

export type OverviewResponse = {
  overview: MonitoringOverview
  timeseries: UsageTimeseriesPoint[]
}

export type UsageRow = {
  key: string
  name: string
  subLabel: string
  agentCount: number | null
  sessionCount: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreateTokens: number
  totalTokens: number
  costUsd: number | null
  callCount: number
  avgLatencyMs: number
  hasIncompleteCost: boolean
}

export type UsageResponse = {
  rows: UsageRow[]
  totalCostUsd: number | null
  truncated: boolean
  groupBy: string
  range: string
}

export type RealtimeStatus = {
  runtimeActive: number
  processing: number
  perOrgActive: { organizationId: string; organizationName: string; count: number }[]
  ts: string
}

export type MonitoringSession = {
  id: string
  name: string
  agentId: string
  agentName: string
  organizationId: string
  organizationName: string
  providerName: string
  isRuntimeActive: boolean
  totalInputTokens: number
  totalOutputTokens: number
  status: string
  durationSeconds: number
  lastActivityAt: string
  messageCount: number
  hasConsumer: boolean
  source: string
  closeReason: string | null
  isProcessing: boolean
  createdAt: string
  updatedAt: string
  closedAt: string | null
  envOverridesSanitized: Array<{ key: string; value: string; masked: boolean }>
}

export type MonitoringSessionsResponse = {
  sessions: MonitoringSession[]
  total: number
  page: number
  pageSize: number
  runtimeActive: number
}

export type BatchStopSessionsResult = {
  requested: number
  stopped: number
  failedIds: string[]
}

export type OverviewData = {
  overview: OverviewResponse
  usage: UsageResponse
  realtime: RealtimeStatus
  sessions: MonitoringSessionsResponse
}

export type AuthenticatedRequest = <T>(path: string, init?: RequestInit) => Promise<T>
