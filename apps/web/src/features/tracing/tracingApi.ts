import type { AuthenticatedRequest } from '../overview/types'
import type { ConversationTraceGroup, TraceDetail, TraceList, TraceStats, TraceSummary } from './types'

export const loadTracing = async (request: AuthenticatedRequest) => {
  const [list, stats] = await Promise.all([
    request<TraceList>('/api/v1/users/tracing/traces?limit=100&offset=0'),
    request<TraceStats>('/api/v1/users/tracing/stats?range=7d'),
  ])
  return { list, stats }
}

export const loadTraceDetail = (request: AuthenticatedRequest, traceId: string) => request<TraceDetail>(
  `/api/v1/users/tracing/traces/${encodeURIComponent(traceId)}`,
)

const modeOf = (trace: TraceSummary): ConversationTraceGroup['mode'] => trace.metadata.projectId || trace.metadata.source === 'project' ? 'project' : 'chat'

export function groupConversationTraces(traces: TraceSummary[]): ConversationTraceGroup[] {
  const grouped = new Map<string, ConversationTraceGroup>()
  for (const trace of traces) {
    const current = grouped.get(trace.sessionId)
    if (current) {
      current.traces.push(trace)
      current.totalTokens += trace.totalTokens
      if (trace.startTime > current.latestAt) current.latestAt = trace.startTime
      continue
    }
    grouped.set(trace.sessionId, {
      sessionId: trace.sessionId,
      sessionName: trace.sessionName || trace.sessionId,
      mode: modeOf(trace),
      projectId: typeof trace.metadata.projectId === 'string' ? trace.metadata.projectId : null,
      traces: [trace],
      totalTokens: trace.totalTokens,
      latestAt: trace.startTime,
    })
  }
  return [...grouped.values()]
    .map((group) => ({ ...group, traces: group.traces.sort((left, right) => right.startTime.localeCompare(left.startTime)) }))
    .sort((left, right) => right.latestAt.localeCompare(left.latestAt))
}
