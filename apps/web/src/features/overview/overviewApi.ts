import type { AuthenticatedRequest, BatchStopSessionsResult, MonitoringSession, MonitoringSessionsResponse, OverviewData, OverviewResponse, RealtimeStatus, UsageResponse, UsageRange } from './types'

export async function loadOverview(
  request: AuthenticatedRequest,
  range: UsageRange,
  sessionPage = 1,
  sessionPageSize = 10,
): Promise<OverviewData> {
  const encodedRange = encodeURIComponent(range)
  const [overview, usage, realtime, sessions] = await Promise.all([
    request<OverviewResponse>(`/api/v1/monitoring/overview?range=${encodedRange}`),
    request<UsageResponse>(`/api/v1/monitoring/usage?range=${encodedRange}&groupBy=agent`),
    request<RealtimeStatus>('/api/v1/monitoring/realtime'),
    request<MonitoringSessionsResponse>(`/api/v1/monitoring/sessions?range=${encodedRange}&status=all&page=${sessionPage}&pageSize=${sessionPageSize}`),
  ])
  return { overview, usage, realtime, sessions }
}

export const loadAgentUsage = (request: AuthenticatedRequest) =>
  request<UsageResponse>('/api/v1/monitoring/usage/agents')

export const stopMonitoringSession = (request: AuthenticatedRequest, sessionId: string) => request<MonitoringSession>(
  `/api/v1/monitoring/sessions/${encodeURIComponent(sessionId)}/stop`,
  { method: 'POST' },
)

export const stopMonitoringSessions = (request: AuthenticatedRequest, sessionIds: string[]) => request<BatchStopSessionsResult>(
  '/api/v1/monitoring/sessions/batch-stop',
  { method: 'POST', body: JSON.stringify({ sessionIds }) },
)
